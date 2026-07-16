package br.leg.senado.nusp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Relógio único da aplicação, injetado no {@code BancoHorasService} (refactor de
 * testabilidade — plano de testes T26a): o service passa a ler o "hoje" do Clock,
 * o que permite fixá-lo nos testes ({@code Clock.fixed}) em vez de construir
 * fixtures relativas ao dia da execução (e pular casos quando o calendário não
 * colabora).
 *
 * <p>{@code systemDefaultZone()} lê a zona default da JVM — que, desde o F7/C17, é
 * {@code America/Sao_Paulo}: o {@code TZ} passou a ser DECLARADO nos containers
 * (docker-compose + Dockerfile.backend), então o "hoje"/"agora" do service resolve em
 * BRT sem precisar fixar zona aqui. Foi assim que o F7 se curou — o corte do dia deixou
 * de escorregar entre 21h e 00h BRT (prazo de retificação, "mês corrente", escala do dia).
 *
 * <p>Por que NÃO fixar {@code America/Sao_Paulo} neste bean: o {@code TZ} do container é a
 * FONTE ÚNICA do fuso — alinha de uma vez a JVM (este Clock, os carimbos {@code CRIADO_EM},
 * as 9 colunas {@code Instant}) E o Oracle (as 15 expressões {@code SYSTIMESTAMP}/
 * {@code SYSDATE} do SQL). Fixar a zona aqui criaria um segundo lugar a declarar o fuso,
 * divergível do container, e ainda não alcançaria nem o Oracle nem a escrita de
 * {@code Instant}. Mantendo {@code systemDefaultZone()}, há um dono só.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
