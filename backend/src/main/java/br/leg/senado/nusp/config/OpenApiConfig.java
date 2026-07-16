package br.leg.senado.nusp.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI nuspOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("NUSP — API de Operações de Áudio e Checklists")
                        .description("API do Sistema de Operações de Áudio e Checklists do Senado Federal (NUSP/SEAP).")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("NUSP/SEAP — Senado Federal")
                                .email("nusp.seap@senado.leg.br")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .schemaRequirement("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Token JWT obtido via POST /api/login"));
    }
}
