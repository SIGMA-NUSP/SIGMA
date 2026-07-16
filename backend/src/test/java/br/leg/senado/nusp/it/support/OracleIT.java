package br.leg.senado.nusp.it.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Anotação-base de todos os testes de integração ({@code *IT}) da suíte.
 *
 * Slice JPA transacional (rollback ao fim de cada teste) apontado pelo
 * perfil "test" para o schema NUSP_TEST — clone de metadados do NUSP no
 * Oracle de homolog local (localhost:1522/XEPDB1). {@code Replace.NONE}
 * impede o Spring de trocar o datasource por um banco embarcado.
 *
 * Pré-requisitos: container nusp-oracle-homolog no ar e schema criado por
 * {@code docker/test-db/recriar-nusp-test.sh}. Executar só via
 * {@code mvn verify -DskipITs=false} — e nunca duas execuções em paralelo
 * (o schema de teste é único e compartilhado).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
public @interface OracleIT {
}
