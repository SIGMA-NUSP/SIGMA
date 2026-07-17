package br.leg.senado.nusp.it.support;

import java.util.List;

import org.slf4j.LoggerFactory;

import br.leg.senado.nusp.service.DashboardQueryHelper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Vigia anti-falso-verde das facetas do {@link DashboardQueryHelper}.
 *
 * <p>A consolidação {@code GROUPING SETS} e o fallback por-coluna produzem o MESMO
 * resultado — só um WARN distingue os caminhos. Sem vigiar o log, todo teste de faceta
 * passaria mesmo com a consolidação quebrada (e a listagem regrediria em silêncio a
 * N+2 queries em produção).
 *
 * <p>Uso: {@code instalar()} no {@code @BeforeEach} e {@code exigirZeroWarns()} no
 * {@code @AfterEach}.
 */
public final class VigiaDeFacetas {

    private final Logger logger = (Logger) LoggerFactory.getLogger(DashboardQueryHelper.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public void instalar() {
        appender.start();
        logger.addAppender(appender);
    }

    /** Falha o teste se o motor tiver caído no fallback por-coluna (único sinal: o WARN). */
    public void exigirZeroWarns() {
        logger.detachAppender(appender);
        List<String> warns = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        if (!warns.isEmpty()) {
            throw new AssertionError(
                    "o motor caiu no fallback por-coluna (a consolidação GROUPING SETS falhou): " + warns);
        }
    }
}
