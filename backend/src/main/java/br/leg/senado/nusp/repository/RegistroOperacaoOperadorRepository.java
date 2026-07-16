package br.leg.senado.nusp.repository;

import br.leg.senado.nusp.entity.RegistroOperacaoOperador;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RegistroOperacaoOperadorRepository extends JpaRepository<RegistroOperacaoOperador, Long> {

    /** Lista entradas da sessão com nome do operador e anormalidade_id. Equivale a listar_entradas_da_sessao(). */
    @Query(value = """
            SELECT e.ID, e.REGISTRO_ID, e.OPERADOR_ID,
                   o.NOME_COMPLETO AS OPERADOR_NOME,
                   e.ORDEM, e.SEQ, e.NOME_EVENTO,
                   e.HORARIO_PAUTA, e.HORARIO_INICIO, e.HORARIO_TERMINO,
                   e.TIPO_EVENTO, e.USB_01, e.USB_02, e.OBSERVACOES,
                   CASE WHEN a.ID IS NOT NULL THEN 1 ELSE 0 END AS HOUVE_ANORMALIDADE,
                   a.ID AS ANORMALIDADE_ID,
                   e.COMISSAO_ID, e.RESPONSAVEL_EVENTO,
                   e.HORA_ENTRADA, e.HORA_SAIDA
            FROM OPR_REGISTRO_ENTRADA e
            JOIN PES_OPERADOR o ON o.ID = e.OPERADOR_ID
            LEFT JOIN OPR_ANORMALIDADE a ON a.ENTRADA_ID = e.ID
            WHERE e.REGISTRO_ID = :registroId
            ORDER BY e.ORDEM ASC, e.ID ASC
            """, nativeQuery = true)
    List<Object[]> listarEntradasDaSessao(@Param("registroId") long registroId);

    /** Snapshot para histórico. Equivale a get_entrada_operacao_snapshot(). */
    @Query(value = """
            SELECT e.NOME_EVENTO, e.RESPONSAVEL_EVENTO, e.HORARIO_PAUTA,
                   e.HORARIO_INICIO, e.HORARIO_TERMINO, e.TIPO_EVENTO,
                   e.USB_01, e.USB_02, e.OBSERVACOES, e.COMISSAO_ID,
                   e.HOUVE_ANORMALIDADE, r.SALA_ID,
                   e.HORA_ENTRADA, e.HORA_SAIDA
            FROM OPR_REGISTRO_ENTRADA e
            JOIN OPR_REGISTRO_AUDIO r ON r.ID = e.REGISTRO_ID
            WHERE e.ID = :entradaId
            """, nativeQuery = true)
    List<Object[]> getSnapshot(@Param("entradaId") long entradaId);

    /** Conta entradas na mesma sessão. Equivale a count_entradas_por_sessao(). */
    @Query(value = """
            SELECT COUNT(*) FROM OPR_REGISTRO_ENTRADA
            WHERE REGISTRO_ID = (SELECT REGISTRO_ID FROM OPR_REGISTRO_ENTRADA WHERE ID = :entradaId)
            """, nativeQuery = true)
    int countEntradasPorSessao(@Param("entradaId") long entradaId);

    /** Retorna registro_id da entrada. */
    @Query("SELECT e.registroId FROM RegistroOperacaoOperador e WHERE e.id = :entradaId")
    Optional<Long> findRegistroIdByEntradaId(@Param("entradaId") long entradaId);

    /** Retorna operador_id da entrada (verificação de ownership). */
    @Query("SELECT e.operadorId FROM RegistroOperacaoOperador e WHERE e.id = :entradaId")
    Optional<String> findOperadorIdByEntradaId(@Param("entradaId") long entradaId);

    /**
     * Verifica se o operador tem acesso a uma entrada — como dono principal
     * (e.OPERADOR_ID) ou como co-operador via OPR_ENTRADA_OPERADOR (Plenário Principal).
     * Retorna > 0 se houver acesso, 0 caso contrário.
     */
    @Query(value = """
            SELECT COUNT(*) FROM OPR_REGISTRO_ENTRADA e
            WHERE e.ID = :entradaId
              AND (e.OPERADOR_ID = :operadorId
                   OR EXISTS (SELECT 1 FROM OPR_ENTRADA_OPERADOR eo
                              WHERE eo.ENTRADA_ID = e.ID AND eo.OPERADOR_ID = :operadorId))
            """, nativeQuery = true)
    int countOperadorAcessoEntrada(@Param("entradaId") long entradaId,
                                    @Param("operadorId") String operadorId);

    /** Marca sala_editado se o valor mudou. */
    @Modifying @Transactional
    @Query(value = """
            UPDATE OPR_REGISTRO_ENTRADA e
            SET SALA_EDITADO = CASE
                WHEN (SELECT r.SALA_ID FROM OPR_REGISTRO_AUDIO r WHERE r.ID = e.REGISTRO_ID) != :novoSalaId
                THEN 1 ELSE SALA_EDITADO END
            WHERE e.ID = :entradaId
            """, nativeQuery = true)
    void marcarSalaEditado(@Param("entradaId") long entradaId, @Param("novoSalaId") int novoSalaId);

    /** Atualiza campos básicos de uma entrada (sem flags de edição). Usado por salvar_entrada. */
    @Modifying @Transactional
    @Query(value = """
            UPDATE OPR_REGISTRO_ENTRADA SET
                NOME_EVENTO = :nomeEvento, HORARIO_PAUTA = :horarioPauta,
                HORARIO_INICIO = :horarioInicio, HORARIO_TERMINO = :horarioTermino,
                TIPO_EVENTO = :tipoEvento, OBSERVACOES = :observacoes,
                USB_01 = :usb01, USB_02 = :usb02,
                COMISSAO_ID = :comissaoId, RESPONSAVEL_EVENTO = :responsavelEvento,
                HORA_ENTRADA = :horaEntrada, HORA_SAIDA = :horaSaida,
                ATUALIZADO_POR = :atualizadoPor, ATUALIZADO_EM = SYSTIMESTAMP
            WHERE ID = :entradaId
            """, nativeQuery = true)
    void updateEntradaBasica(
            @Param("entradaId") long entradaId,
            @Param("nomeEvento") String nomeEvento,
            @Param("horarioPauta") String horarioPauta,
            @Param("horarioInicio") String horarioInicio,
            @Param("horarioTermino") String horarioTermino,
            @Param("tipoEvento") String tipoEvento,
            @Param("observacoes") String observacoes,
            @Param("usb01") String usb01,
            @Param("usb02") String usb02,
            @Param("comissaoId") Long comissaoId,
            @Param("responsavelEvento") String responsavelEvento,
            @Param("horaEntrada") String horaEntrada,
            @Param("horaSaida") String horaSaida,
            @Param("atualizadoPor") String atualizadoPor
    );

    /**
     * Busca dados para pré-preenchimento de anormalidade.
     *
     * <p>A entrada tem de pertencer ao registro consultado ({@code e.REGISTRO_ID = r.ID}): sem
     * essa correlação, uma {@code entrada_id} de OUTRO registro pré-preenchia o formulário com
     * evento/responsável alheios. Par incoerente devolve o registro com as colunas de entrada
     * nulas — o mesmo shape de uma {@code entrada_id} inexistente.
     */
    @Query(value = """
            SELECT r.ID, r.DATA, r.SALA_ID,
                   e.NOME_EVENTO, e.RESPONSAVEL_EVENTO,
                   r.NOME_DEMAIS_SALAS
            FROM OPR_REGISTRO_AUDIO r
            LEFT JOIN OPR_REGISTRO_ENTRADA e ON e.ID = :entradaId AND e.REGISTRO_ID = r.ID
            WHERE r.ID = :registroId
            """, nativeQuery = true)
    List<Object[]> findDadosParaAnormalidade(@Param("registroId") long registroId,
                                             @Param("entradaId") Long entradaId);
}
