package br.leg.senado.nusp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Bean único de {@link HttpClient} da aplicação, injetado no
 * {@code AgendaLegislativaService} (refactor de testabilidade — plano de testes T9/D7).
 *
 * Replica literalmente a construção do antigo campo estático do service; NÃO trocar por
 * {@code HttpClient.newHttpClient()}, cujos defaults diferem (sem connectTimeout e
 * {@code Redirect.NEVER}) e mudariam o comportamento contra a API do Senado.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
