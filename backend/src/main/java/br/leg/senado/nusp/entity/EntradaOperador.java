package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** OPR_ENTRADA_OPERADOR — operadores de uma entrada ROA (Plenário Principal) */
@Entity
@Table(name = "OPR_ENTRADA_OPERADOR")
@Getter @Setter
public class EntradaOperador {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ENTRADA_ID", nullable = false)
    private Long entradaId;

    @Column(name = "OPERADOR_ID", nullable = false)
    private String operadorId;
}
