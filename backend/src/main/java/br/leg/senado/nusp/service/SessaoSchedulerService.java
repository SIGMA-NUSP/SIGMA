package br.leg.senado.nusp.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fecha automaticamente sessões de operação (OPR_REGISTRO_AUDIO) que ficaram
 * em aberto de dias anteriores. Equivale ao management command
 * fechar_sessoes_abertas.py do Django.
 *
 * Executa diariamente às 06:00 BRT (os containers rodam em America/Sao_Paulo — F7/C17).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessaoSchedulerService {

    private final EntityManager em;

    /**
     * "Hora de saída efetiva" de uma entrada, para consumo do job (F73/C19): a escrita já
     * recusa hora torta na porta ({@link HoraValidator}), mas a faxina TOLERA o lixo que
     * porventura exista — sem isso, uma única HORA_SAIDA torta estourava o TO_DSINTERVAL
     * (ORA-01850/ORA-01867, medidos) e, como cada UPDATE é único, NENHUMA sessão fechava.
     *
     * <p>Mapeamento: '24:00:00' exato → '23:59:59' (fim visível, assunção do Douglas:
     * 24:00 nunca é fim legítimo); HH:MM:SS válido (00–23/00–59) → ele mesmo; qualquer
     * outro valor → NULL (a entrada não concorre a HORARIO_TERMINO, MAX nem FECHADO_POR —
     * sem o filtro, um 'xx' lexicograficamente maior venceria o MAX e atribuiria o
     * fechamento ao operador errado). A HORA_SAIDA torta em si NÃO é reescrita.
     */
    private static String horaSaidaEfetiva(String col) {
        return "CASE WHEN " + col + " = '24:00:00' THEN '23:59:59' "
                + "WHEN REGEXP_LIKE(" + col + ", '^([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d$') THEN " + col + " "
                + "ELSE NULL END";
    }

    @Scheduled(cron = "0 0 6 * * *") // 06:00 BRT (container em America/Sao_Paulo — antes era 09:00 UTC, F7/C17)
    @Transactional
    public void fecharSessoesAbertas() {
        log.info("[fechar_sessoes] Iniciando fechamento automático de sessões abertas...");

        // Query 1 — Corrige entradas: copia hora_saida → horario_termino onde está NULL.
        // Desde o C19 só valores VÁLIDOS são copiados (com 24:00:00 → 23:59:59); uma torta
        // qualquer fica de fora e o HORARIO_TERMINO permanece nulo — o relatório mostra
        // "Evento não encerrado" (RdsXlsxService.computeFimSessao já trata).
        int corrigidas = em.createNativeQuery("""
                UPDATE OPR_REGISTRO_ENTRADA e SET
                    e.HORARIO_TERMINO = %s
                WHERE e.REGISTRO_ID IN (
                    SELECT r.ID FROM OPR_REGISTRO_AUDIO r
                    WHERE r.EM_ABERTO = 1 AND r.DATA < TRUNC(SYSDATE)
                )
                AND e.HORARIO_TERMINO IS NULL
                AND %s IS NOT NULL
                """.formatted(horaSaidaEfetiva("e.HORA_SAIDA"), horaSaidaEfetiva("e.HORA_SAIDA")))
                .executeUpdate();

        // Query 2 — Fecha as sessões
        // FECHADO_EM = DATA da sessão + maior HORA_SAIDA efetiva (VARCHAR HH:MM:SS) → TIMESTAMP
        // FECHADO_POR = OPERADOR_ID da última entrada com saída VÁLIDA
        //
        // A guarda CASE (F17/C16): sessão esquecida SEM nenhuma HORA_SAIDA (aproveitável) tem
        // MAX(...) = NULL, e TO_DSINTERVAL('0 ' || NULL) = TO_DSINTERVAL('0 ') estoura ORA-01867
        // — abortando o UPDATE inteiro, ou seja, uma sessão degenerada impedia o fechamento de
        // TODAS as outras. Com o CASE o TO_DSINTERVAL não é avaliado nesse caminho: a sessão
        // fecha (EM_ABERTO = 0, pela cláusula externa) com FECHADO_EM nulo. Desde o C19 a hora
        // EFETIVA (ver horaSaidaEfetiva) compõe com a guarda: torta vira NULL, sai do MAX, e a
        // sessão só-com-torta cai no mesmo caminho degenerado (fecha com carimbo em branco).
        int fechadas = em.createNativeQuery("""
                UPDATE OPR_REGISTRO_AUDIO r SET
                    r.EM_ABERTO = 0,
                    r.FECHADO_EM = (
                        SELECT CASE
                                 WHEN MAX(%s) IS NULL THEN NULL
                                 ELSE CAST(r.DATA AS TIMESTAMP) + TO_DSINTERVAL('0 ' || MAX(%s))
                               END
                        FROM OPR_REGISTRO_ENTRADA e
                        WHERE e.REGISTRO_ID = r.ID AND e.HORA_SAIDA IS NOT NULL
                    ),
                    r.FECHADO_POR = (
                        SELECT e2.OPERADOR_ID FROM OPR_REGISTRO_ENTRADA e2
                        WHERE e2.REGISTRO_ID = r.ID AND %s IS NOT NULL
                        ORDER BY %s DESC FETCH FIRST 1 ROW ONLY
                    )
                WHERE r.EM_ABERTO = 1
                AND r.DATA < TRUNC(SYSDATE)
                """.formatted(horaSaidaEfetiva("e.HORA_SAIDA"), horaSaidaEfetiva("e.HORA_SAIDA"),
                        horaSaidaEfetiva("e2.HORA_SAIDA"), horaSaidaEfetiva("e2.HORA_SAIDA")))
                .executeUpdate();

        log.info("[fechar_sessoes] Concluído: {} entradas corrigidas, {} sessões fechadas.", corrigidas, fechadas);
    }
}
