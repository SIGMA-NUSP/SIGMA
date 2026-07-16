package br.leg.senado.nusp.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;
import java.util.UUID;

import org.hibernate.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import br.leg.senado.nusp.entity.PasswordResetToken;
import br.leg.senado.nusp.entity.RegistroOperacaoAudio;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.it.support.OracleIT;
import br.leg.senado.nusp.repository.PasswordResetTokenRepository;
import jakarta.persistence.EntityManager;

/**
 * Prova empírica, contra Oracle real, do que o fuso da JVM faz com a ESCRITA de {@code Instant} —
 * e de por que as 8 colunas {@code Instant} SEM fuso entram na conversão −3h do F7/C17.
 *
 * <p>Contexto: a property {@code hibernate.type.preferred_instant_jdbc_type: TIMESTAMP} (cura do
 * F14, C5) fez a escrita de {@code Instant} deixar de ser imune ao fuso da JVM — o Hibernate passou
 * a usar {@code setTimestamp()}, que grava o wall-clock da zona default. Enquanto a JVM rodava em
 * UTC (containers sem TZ declarado), esse wall-clock era o UTC — e foi assim que TODO o histórico do
 * banco foi gravado. O F7/C17 declarou {@code TZ=America/Sao_Paulo} nos containers: a partir de
 * agora a app grava o wall-clock BRT. As duas representações do MESMO instante divergem em 3h — e é
 * exatamente esse −3h que a migração aplica ao histórico para reconciliá-lo com a escrita nova.
 *
 * <p>Este IT mede as duas escritas lado a lado: a de HOJE (Hibernate+property → {@code setTimestamp}
 * na zona da JVM, BRT) e a que PRODUZIU o histórico (JDBC puro,
 * {@code setObject(OffsetDateTime em UTC)} — o bind que o TIMESTAMP_UTC fazia). Sem essa comparação,
 * um teste que só olhasse a escrita nova não teria poder de detecção: é preciso confrontá-la com o
 * modo histórico para ver o deslocamento de 3h.
 *
 * <p>O segundo @Nested guarda o único {@code Instant} que a APLICAÇÃO lê por JPA hoje:
 * {@code PES_PASSWORD_RESET.EXPIRES_AT}, do fluxo "esqueci minha senha"
 * ({@code PasswordResetService.validateToken/resetPassword} → {@code findByToken}). É a única coluna
 * de carimbo do schema declarada {@code TIMESTAMP(6) WITH TIME ZONE} — e por carregar o offset é
 * fuso-neutra: o INSTANTE continua correto e ela fica de FORA da conversão −3h (converter uma coluna
 * com fuso moveria o instante). O que muda com a JVM em BRT é só a REPRESENTAÇÃO gravada (o offset
 * passa de {@code +00:00} para {@code -03:00}, mesmo instante).
 */
@OracleIT
class InstantJdbcTypeIT {

    /** Instante fixo de referência: minuto redondo, sem fração — o que se grava é o que se lê. */
    private static final Instant INSTANTE = Instant.parse("2026-07-12T15:00:00Z");

    @Autowired
    private TestEntityManager em;

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    private EntityManager emReal() {
        return em.getEntityManager();
    }

    @Nested
    @DisplayName("escrita em coluna TIMESTAMP (sem fuso)")
    class ColunaSemFuso {

        /** Wall-clock realmente gravado na coluna, sem passar por conversão de fuso na volta. */
        private String wallClockDe(Long id) {
            return (String) emReal().createNativeQuery("""
                    SELECT TO_CHAR(FECHADO_EM, 'YYYY-MM-DD HH24:MI:SS')
                    FROM OPR_REGISTRO_AUDIO WHERE ID = :id
                    """)
                    .setParameter("id", id)
                    .getSingleResult();
        }

        @Test
        @DisplayName("F7/C17 — a escrita de hoje grava 12:00 BRT, que é o histórico UTC 15:00 menos 3h: "
                + "o deslocamento que a conversão −3h reconcilia")
        void escritaNova_ehBrt_e_menos3hDoHistoricoUtc() {
            assertEquals("America/Sao_Paulo", TimeZone.getDefault().getID(),
                    "pré-condição desta medição (pin do argLine, pom): a JVM do failsafe tem de estar em"
                            + " America/Sao_Paulo, como os containers (F7/C17) — é o fuso da JVM que a property"
                            + " tornou relevante para a escrita de Instant");

            Sala sala = CenarioFactory.novaSala(emReal());
            RegistroOperacaoAudio pelaEscritaBrt = CenarioFactory.novoRegistroAudio(emReal(), sala, false);
            RegistroOperacaoAudio peloModoHistoricoUtc = CenarioFactory.novoRegistroAudio(emReal(), sala, false);
            pelaEscritaBrt.setFechadoEm(INSTANTE);    // caminho de HOJE: Hibernate + property → setTimestamp() na zona da JVM (BRT)
            emReal().flush();

            // Caminho que PRODUZIU o histórico do banco (byte a byte): antes do TZ nos containers a JVM
            // rodava em UTC, e este setObject(OffsetDateTime em UTC) grava o mesmo wall-clock UTC que todo
            // o histórico carrega (15:00). É contra ESTE valor que a conversão −3h da migração opera.
            emReal().unwrap(Session.class).doWork(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE OPR_REGISTRO_AUDIO SET FECHADO_EM = ? WHERE ID = ?")) {
                    ps.setObject(1, OffsetDateTime.ofInstant(INSTANTE, ZoneOffset.UTC));
                    ps.setLong(2, peloModoHistoricoUtc.getId());
                    ps.executeUpdate();
                }
            });

            String escritaBrt = wallClockDe(pelaEscritaBrt.getId());
            String historicoUtc = wallClockDe(peloModoHistoricoUtc.getId());

            assertEquals("2026-07-12 12:00:00", escritaBrt,
                    "a escrita de HOJE grava o wall-clock BRT do Instant (a JVM está em America/Sao_Paulo,"
                            + " como os containers): 15:00Z = 12:00 em Brasília");
            assertEquals("2026-07-12 15:00:00", historicoUtc,
                    "o histórico do banco está em wall-clock UTC — é o que o caminho de antes do TZ produziu,"
                            + " e o que a migração −3h precisa reconciliar");
            // A prova que amarra este IT à conversão §4.5: aplicar −3h ao histórico UTC dá exatamente a
            // escrita nova em BRT. É por isso que as 8 colunas Instant sem fuso ENTRAM na conversão.
            assertEquals(LocalDateTime.parse(escritaBrt.replace(' ', 'T')),
                    LocalDateTime.parse(historicoUtc.replace(' ', 'T')).minusHours(3),
                    "MEDIÇÃO que sustenta a conversão de fuso: histórico(UTC) − 3h == escrita nova(BRT) — o"
                            + " −3h da migração traz o histórico para o mesmo wall-clock que a app passa a gravar");
        }
    }

    @Nested
    @DisplayName("PES_PASSWORD_RESET.EXPIRES_AT — TIMESTAMP WITH TIME ZONE")
    class ColunaComFuso {

        private PasswordResetToken novoToken(Instant expiraEm) {
            PasswordResetToken token = new PasswordResetToken();
            token.setUserId(UUID.randomUUID().toString());
            token.setUserType("operador");
            token.setToken(UUID.randomUUID().toString());
            token.setExpiresAt(expiraEm);
            token.setUsed(false);
            emReal().persist(token);
            emReal().flush();
            emReal().clear();
            return token;
        }

        @Test
        @DisplayName("corrige F14 — findByToken devolve EXPIRES_AT intacto (a única leitura JPA de Instant que a app faz hoje)")
        void findByToken_devolveExpiresAtIntacto() {
            PasswordResetToken token = novoToken(INSTANTE);

            PasswordResetToken lido = tokenRepository.findByToken(token.getToken()).orElse(null);

            assertNotNull(lido, "o fluxo do reset lê o token por esta derived query (validateToken/resetPassword)");
            assertEquals(INSTANTE, lido.getExpiresAt(),
                    "a property é GLOBAL: ela troca o tipo JDBC também nesta coluna, a única WITH TIME ZONE do"
                            + " schema — a que não precisava da cura. Este é o gate de que não a quebrou.");
        }

        @Test
        @DisplayName("F7/C17 — a coluna com fuso guarda o mesmo INSTANTE, só a representação passa a carregar offset -03:00 (nada a migrar aqui)")
        void escritaEmColunaComFuso_mantemOInstante_representacaoEmBrt() {
            PasswordResetToken token = novoToken(INSTANTE);

            // TZH:TZM (e não SYS_EXTRACT_UTC): aqui interessa o que ficou GRAVADO, incluindo o offset —
            // normalizar para UTC apagaria justamente a diferença que este teste existe para vigiar.
            // O histórico de PES_PASSWORD_RESET (gravado quando a JVM era UTC) está em +00:00; o driver
            // carimba o offset da sessão JDBC, que segue o fuso da JVM — em BRT (F7/C17) vem
            // "12:00:00 -03:00": MESMO instante, outra representação. Por ser WITH TIME ZONE, a coluna é
            // fuso-neutra na leitura (o Instant volta idêntico) — é por isso que ela fica FORA da
            // conversão −3h: mover uma coluna com fuso deslocaria o instante, não o corrigiria.
            String gravado = (String) emReal().createNativeQuery("""
                    SELECT TO_CHAR(EXPIRES_AT, 'YYYY-MM-DD HH24:MI:SS TZH:TZM')
                    FROM PES_PASSWORD_RESET WHERE ID = :id
                    """)
                    .setParameter("id", token.getId())
                    .getSingleResult();

            assertEquals("2026-07-12 12:00:00 -03:00", gravado,
                    "com a JVM em BRT (containers e, pelo pin, os testes — F7/C17), a coluna com fuso carimba o"
                            + " offset -03:00; é o MESMO instante do histórico +00:00, só outra representação — e é"
                            + " por isso que EXPIRES_AT fica de FORA da conversão −3h (a coluna com fuso não pode mover)");
        }

        @Test
        @DisplayName("expiração do token continua decidida pelo instante, não pelo wall-clock (validateToken)")
        void tokenExpirado_ehReconhecidoPeloInstante() {
            PasswordResetToken expirado = novoToken(Instant.now().minusSeconds(60));
            PasswordResetToken valido = novoToken(Instant.now().plusSeconds(1800));

            assertTrue(tokenRepository.findByToken(expirado.getToken()).orElseThrow()
                    .getExpiresAt().isBefore(Instant.now()),
                    "token vencido há 1 min tem de continuar vencido depois do round-trip (é a conta do validateToken)");
            assertTrue(tokenRepository.findByToken(valido.getToken()).orElseThrow()
                    .getExpiresAt().isAfter(Instant.now()),
                    "token com 30 min de vida tem de continuar válido — um deslocamento de 3h o mataria");
        }
    }
}
