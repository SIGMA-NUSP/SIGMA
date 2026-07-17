package br.leg.senado.nusp.controller.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import br.leg.senado.nusp.config.JwtConfig;
import br.leg.senado.nusp.config.SecurityConfig;
import br.leg.senado.nusp.repository.AuthSessionRepository;
import br.leg.senado.nusp.security.JwtAuthenticationFilter;
import br.leg.senado.nusp.security.JwtTokenProvider;

/**
 * Anotação-base dos testes de controller ({@code @WebMvcTest}) com a
 * segurança REAL do app: {@link SecurityConfig} (matchers de rota),
 * {@link JwtAuthenticationFilter} (que responde 401 por conta própria) e
 * {@link JwtTokenProvider} (tokens HS256 reais, secret do perfil "test").
 *
 * O post-processor user() do spring-security-test não funciona neste
 * backend — o filtro ignora SecurityContext pré-populado e os controllers
 * exigem {@code @AuthenticationPrincipal UserPrincipal} — request
 * autenticada = token do {@link TokenFactory}.
 *
 * O filtro exige um bean {@link AuthSessionRepository}: o mock é registrado
 * aqui na meta-anotação ({@code @MockitoBean(types=...)}), mas o default do
 * Mockito é 0 (= sessão inválida → 401): cada classe de teste deve injetá-lo
 * com {@code @Autowired} e stubar {@code touchSession(...) → 1} no setup.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@WebMvcTest
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtTokenProvider.class})
@EnableConfigurationProperties(JwtConfig.class)
@MockitoBean(types = AuthSessionRepository.class)
@ActiveProfiles("test")
public @interface SigmaControllerTest {

    /** Controllers do slice — repassado a {@link WebMvcTest#controllers()}. */
    @AliasFor(annotation = WebMvcTest.class, attribute = "controllers")
    Class<?>[] value() default {};
}
