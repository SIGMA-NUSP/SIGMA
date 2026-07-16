package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** OPR_ESCALA_SEMANAL — escala semanal de operadores por plenário */
@Entity
@Table(name = "OPR_ESCALA_SEMANAL")
@Getter @Setter
public class EscalaSemanal extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "DATA_INICIO", nullable = false)
    private LocalDate dataInicio;

    @Column(name = "DATA_FIM", nullable = false)
    private LocalDate dataFim;

    @Column(name = "CRIADO_POR")
    private String criadoPor;
}
