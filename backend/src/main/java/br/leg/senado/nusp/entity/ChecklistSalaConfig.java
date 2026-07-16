package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * FRM_CHECKLIST_SALA_CONFIG — era forms.checklist_sala_config.
 *
 * <p>F52: o carimbo vem da {@link AuditableEntity}, como nas demais tabelas — os callbacks locais
 * duplicados escapariam de qualquer correção futura de relógio feita na base. As colunas
 * CRIADO_EM/ATUALIZADO_EM são nullable NESTA tabela (herança do PG); os callbacks sempre preenchem
 * antes do INSERT/UPDATE e o validate do Hibernate não confere nullability, então o mapeamento
 * mais estrito da base não muda nada em runtime.
 */
@Entity
@Table(name = "FRM_CHECKLIST_SALA_CONFIG")
@Getter @Setter
public class ChecklistSalaConfig extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "SALA_ID", nullable = false)
    private Integer salaId;

    @Column(name = "ITEM_TIPO_ID", nullable = false)
    private Integer itemTipoId;

    @Column(nullable = false)
    private Integer ordem = 1;

    @Column(nullable = false)
    private Boolean ativo = true;
}
