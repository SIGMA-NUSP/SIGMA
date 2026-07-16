package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** FRM_CHECKLIST_HISTORICO — era forms.checklist_historico (snapshot imutável) */
@Entity
@Table(name = "FRM_CHECKLIST_HISTORICO")
@Getter @Setter
public class ChecklistHistorico {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "CHECKLIST_ID", nullable = false)
    private Long checklistId;

    @Column(nullable = false, columnDefinition = "CLOB")
    private String snapshot;  // jsonb → CLOB; serializado via Jackson

    @Column(name = "EDITADO_POR")
    private String editadoPor;

    @Column(name = "EDITADO_EM", nullable = false, updatable = false)
    private Instant editadoEm;

    @PrePersist
    protected void onCreate() {
        this.editadoEm = Instant.now();
    }
}
