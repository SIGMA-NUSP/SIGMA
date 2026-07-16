package br.leg.senado.nusp.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.jwt")
@Getter @Setter
public class JwtConfig {
    private String secret;
    private int ttlSeconds = 5400;
    private String cookieName = "sn_auth_jwt";
    private String cookieDomain = "";
}
