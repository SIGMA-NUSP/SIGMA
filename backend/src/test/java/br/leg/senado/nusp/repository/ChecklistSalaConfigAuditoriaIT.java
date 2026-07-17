package br.leg.senado.nusp.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import br.leg.senado.nusp.entity.ChecklistItemTipo;
import br.leg.senado.nusp.entity.ChecklistSalaConfig;
import br.leg.senado.nusp.entity.Sala;
import br.leg.senado.nusp.it.support.CenarioFactory;
import br.leg.senado.nusp.it.support.OracleIT;
import jakarta.persistence.EntityManager;

/**
 * COMPORTAMENTO do carimbo herdado da {@code AuditableEntity}, contra Oracle real
 * (a forma — herança sem callbacks próprios — está no unit {@code ChecklistSalaConfigTest}).
 *
 * <p>Nuance: as colunas CRIADO_EM/ATUALIZADO_EM são <b>nullable</b> nesta tabela (linhas
 * legadas da migração do PostgreSQL têm NULL), enquanto a superclasse mapeia
 * {@code nullable=false} (+ {@code updatable=false} no CRIADO_EM). O {@code ddl-auto: validate}
 * não confere nullability e o classpath tem Bean Validation (que assume o check de nulidade do
 * Hibernate — e a entidade não tem {@code @NotNull}), então nada muda em runtime.
 */
@OracleIT
class ChecklistSalaConfigAuditoriaIT {

    @Autowired
    private ChecklistSalaConfigRepository salaConfigRepo;

    @Autowired
    private TestEntityManager em;

    private EntityManager emReal() {
        return em.getEntityManager();
    }

    @Test
    @DisplayName("INSERT carimba criadoEm/atualizadoEm; UPDATE muda só o atualizadoEm")
    void carimboHerdado_insertEUpdate() {
        Sala sala = CenarioFactory.novaSala(emReal());
        ChecklistItemTipo tipo = CenarioFactory.novoItemTipo(emReal());

        ChecklistSalaConfig config = CenarioFactory.novaConfig(emReal(), sala, tipo, 1, true);

        LocalDateTime criadoNoInsert = config.getCriadoEm();
        LocalDateTime atualizadoNoInsert = config.getAtualizadoEm();
        assertNotNull(criadoNoInsert, "@PrePersist da base deve carimbar CRIADO_EM");
        assertNotNull(atualizadoNoInsert, "@PrePersist da base deve carimbar ATUALIZADO_EM");

        config.setOrdem(7);
        emReal().flush();

        assertEquals(criadoNoInsert, config.getCriadoEm(), "UPDATE não mexe no CRIADO_EM");
        assertFalse(config.getAtualizadoEm().isBefore(atualizadoNoInsert),
                "@PreUpdate da base deve re-carimbar ATUALIZADO_EM");
    }

    @Test
    @DisplayName("linha legada com carimbos NULL (herança do PG) segue legível e atualizável")
    void linhaLegadaComCarimboNull_legivelEAtualizavel() {
        Sala sala = CenarioFactory.novaSala(emReal());
        ChecklistItemTipo tipo = CenarioFactory.novoItemTipo(emReal());

        // A linha como o PG a deixou: carimbos NULL, gravada por fora do JPA.
        emReal().createNativeQuery(
                "INSERT INTO FRM_CHECKLIST_SALA_CONFIG (SALA_ID, ITEM_TIPO_ID, ORDEM, ATIVO, CRIADO_EM, ATUALIZADO_EM) "
                        + "VALUES (:sala, :tipo, 1, 1, NULL, NULL)")
                .setParameter("sala", sala.getId())
                .setParameter("tipo", tipo.getId())
                .executeUpdate();
        emReal().clear();

        ChecklistSalaConfig legada = salaConfigRepo.findBySalaIdAndItemTipoId(sala.getId(), tipo.getId())
                .orElseThrow();
        assertNull(legada.getCriadoEm(), "o NULL legado continua legível");

        // O fluxo real do AdminCrudService (upsert reativa a config): o UPDATE não pode quebrar.
        legada.setAtivo(false);
        salaConfigRepo.save(legada);
        emReal().flush();

        assertNotNull(legada.getAtualizadoEm(), "@PreUpdate carimba mesmo sobre a linha legada");
        assertTrue(salaConfigRepo.findBySalaIdAndItemTipoId(sala.getId(), tipo.getId()).isPresent());
    }
}
