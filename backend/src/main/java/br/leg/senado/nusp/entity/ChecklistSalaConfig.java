package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** FRM_CHECKLIST_SALA_CONFIG — era forms.checklist_sala_config */
@Entity
@Table(name = "FRM_CHECKLIST_SALA_CONFIG")
@Getter @Setter
public class ChecklistSalaConfig {

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

    // criado_em e atualizado_em são nullable nesta tabela (igual ao PG original)
    @Column(name = "CRIADO_EM")
    private LocalDateTime criadoEm;

    @Column(name = "ATUALIZADO_EM")
    private LocalDateTime atualizadoEm;

    @PrePersist
    protected void onCreate() {
        this.criadoEm = LocalDateTime.now();
        this.atualizadoEm = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.atualizadoEm = LocalDateTime.now();
    }
}
