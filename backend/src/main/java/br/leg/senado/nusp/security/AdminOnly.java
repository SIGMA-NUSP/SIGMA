package br.leg.senado.nusp.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Exige perfil de administrador no próprio método (method security).
 *
 * <p>Redundante — de propósito — com o matcher {@code /api/admin/**} do
 * {@code SecurityConfig}: é a defesa em profundidade do achado F2. Os controllers
 * "mistos" ({@code AvisoController}, {@code EscalaSemanalController},
 * {@code PontoController}) não têm {@code @RequestMapping} de classe e declaram
 * rotas admin e comuns lado a lado; sem esta anotação, uma rota administrativa
 * criada fora do prefixo {@code /api/admin} nasceria apenas {@code authenticated()}.
 *
 * <p>A authority conferida é a que o {@link UserPrincipal} emite —
 * {@code "ROLE_" + role.toUpperCase()}, ou seja {@code ROLE_ADMINISTRADOR} para o
 * perfil {@code administrador} do JWT —, a mesma que o {@code hasRole("ADMINISTRADOR")}
 * do matcher exige. Trocar o perfil emitido exige atualizar os dois lugares.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@PreAuthorize("hasRole('ADMINISTRADOR')")
public @interface AdminOnly {
}
