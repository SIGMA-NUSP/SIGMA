package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** OPR_SUSPENSAO — pares suspende/reabre de uma sessão plenária */
@Entity
@Table(name = "OPR_SUSPENSAO")
@Getter @Setter
public class Suspensao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ENTRADA_ID", nullable = false)
    private Long entradaId;

    @Column(name = "HORA_SUSPENSAO")
    private String horaSuspensao;

    @Column(name = "HORA_REABERTURA")
    private String horaReabertura;

    @Column(nullable = false)
    private Integer ordem;
}
