package br.leg.senado.nusp.entity;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F52 (C18): a {@link ChecklistSalaConfig} reimplementava {@code @PrePersist}/{@code @PreUpdate}
 * com {@code LocalDateTime.now()} próprios em vez de estender {@link AuditableEntity} — um ponto
 * de carimbo que escaparia de qualquer correção futura de relógio feita na base (a lição do C17:
 * o fuso virou contrato de dados, e a base é o lugar único de carimbar).
 *
 * <p>Este teste trava a FORMA da correção: herda da base e não redeclara nem callbacks nem os
 * campos de carimbo. O COMPORTAMENTO (carimbos preenchidos no INSERT, só {@code atualizadoEm}
 * mudando no UPDATE, linha legada com NULL continuando operável) está no IT
 * ({@code ChecklistSalaConfigAuditoriaIT}), contra Oracle real.
 */
class ChecklistSalaConfigTest {

    @Test
    @DisplayName("corrige F52 — ChecklistSalaConfig estende AuditableEntity e NÃO redeclara callbacks nem campos de carimbo")
    void herdaCarimboDaBase_semCallbacksProprios() {
        assertThat(AuditableEntity.class).isAssignableFrom(ChecklistSalaConfig.class);

        Method[] proprios = ChecklistSalaConfig.class.getDeclaredMethods();
        assertThat(Arrays.stream(proprios)
                .filter(m -> m.isAnnotationPresent(PrePersist.class) || m.isAnnotationPresent(PreUpdate.class)))
                .as("callbacks de carimbo devem vir SÓ da AuditableEntity")
                .isEmpty();

        assertThat(Arrays.stream(ChecklistSalaConfig.class.getDeclaredFields()).map(f -> f.getName()))
                .as("os campos de carimbo devem vir SÓ da AuditableEntity")
                .doesNotContain("criadoEm", "atualizadoEm");
    }
}
