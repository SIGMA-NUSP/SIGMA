package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.RegistroOperacaoAudio;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Map;

public interface RegistroOperacaoAudioRepository extends JpaRepository<RegistroOperacaoAudio, Long> {

    /**
     * Busca sessão aberta (em_aberto = 1) para uma sala. Equivale a get_sessao_aberta_por_sala().
     * <p>O WHERE usa a forma da índice-função {@code UQ_OPR_REGAUDIO_SALA_ABERTA}
     * (CASE WHEN EM_ABERTO = 1 THEN SALA_ID END) em vez de {@code SALA_ID = :salaId AND EM_ABERTO = 1}:
     * são equivalentes (para EM_ABERTO &lt;&gt; 1 o CASE resulta NULL e a linha é descartada), mas a
     * forma FBI deixa o Oracle acessar direto o índice funcional — que indexa só sessões abertas,
     * ≤ 1 por sala — em vez de varrer IX_OPR_REGAUDIO_SALA por SALA_ID e filtrar EM_ABERTO. [Q12]
     */
    @Query(value = """
            SELECT r.ID, r.DATA, r.SALA_ID,
                   s.NOME AS SALA_NOME,
                   r.CHECKLIST_DO_DIA_ID,
                   r.CHECKLIST_DO_DIA_OK,
                   r.NOME_DEMAIS_SALAS
            FROM OPR_REGISTRO_AUDIO r
            JOIN CAD_SALA s ON s.ID = r.SALA_ID
            /* Alinhado à FBI UQ_OPR_REGAUDIO_SALA_ABERTA (ver Javadoc); equivale a SALA_ID = :salaId AND EM_ABERTO = 1. */
            WHERE (CASE WHEN r.EM_ABERTO = 1 THEN r.SALA_ID ELSE NULL END) = :salaId
            ORDER BY r.ID DESC
            FETCH FIRST 1 ROWS ONLY
            """, nativeQuery = true)
    List<Object[]> findSessaoAbertaPorSala(@Param("salaId") int salaId);

    /** Verifica checklist do dia para uma sala+data. */
    @Query(value = """
            SELECT c.ID,
                   CASE WHEN EXISTS (
                       SELECT 1 FROM FRM_CHECKLIST_RESPOSTA r
                       WHERE r.CHECKLIST_ID = c.ID AND r.STATUS = 'Falha'
                   ) THEN 0 ELSE 1 END AS OK
            FROM FRM_CHECKLIST c
            WHERE c.DATA_OPERACAO = TO_DATE(:data, 'YYYY-MM-DD')
              AND c.SALA_ID = :salaId
            ORDER BY c.ID DESC
            FETCH FIRST 1 ROWS ONLY
            """, nativeQuery = true)
    List<Object[]> findChecklistDoDia(@Param("data") String data, @Param("salaId") int salaId);

    /** Finaliza sessão. Equivale a finalizar_sessao_operacao_audio(). */
    @Modifying @Transactional
    @Query(value = """
            UPDATE OPR_REGISTRO_AUDIO
            SET EM_ABERTO = 0, FECHADO_EM = SYSTIMESTAMP, FECHADO_POR = :fechadoPor
            WHERE ID = :registroId AND EM_ABERTO = 1
            """, nativeQuery = true)
    int finalizarSessao(@Param("registroId") long registroId, @Param("fechadoPor") String fechadoPor);

    /** Atualiza sala_id no registro de áudio (edição de sala). */
    @Modifying @Transactional
    @Query(value = """
            UPDATE OPR_REGISTRO_AUDIO
            SET SALA_ID = :novoSalaId
            WHERE ID = (SELECT REGISTRO_ID FROM OPR_REGISTRO_ENTRADA WHERE ID = :entradaId)
            """, nativeQuery = true)
    void updateSalaByEntrada(@Param("entradaId") long entradaId, @Param("novoSalaId") int novoSalaId);
}
