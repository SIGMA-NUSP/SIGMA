package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** FRM_CHECKLIST_OPERADOR — operadores do checklist (Plenário Principal) */
@Entity
@Table(name = "FRM_CHECKLIST_OPERADOR")
@Getter @Setter
public class ChecklistOperador {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "CHECKLIST_ID", nullable = false)
    private Long checklistId;

    @Column(name = "OPERADOR_ID", nullable = false)
    private String operadorId;

    /** CABINE ou PLENARIO */
    @Column(nullable = false)
    private String papel;
}
