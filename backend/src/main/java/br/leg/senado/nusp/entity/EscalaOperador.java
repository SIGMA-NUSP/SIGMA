package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** OPR_ESCALA_OPERADOR — vínculo operador ↔ sala numa escala semanal */
@Entity
@Table(name = "OPR_ESCALA_OPERADOR")
@Getter @Setter
public class EscalaOperador {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ESCALA_ID", nullable = false)
    private Long escalaId;

    @Column(name = "SALA_ID", nullable = false)
    private Integer salaId;

    @Column(name = "OPERADOR_ID", nullable = false)
    private String operadorId;

    /** Turno do operador no momento em que a escala foi salva ('M' ou 'V'). */
    @Column(name = "TURNO", nullable = false, length = 1)
    private String turno = "M";
}
