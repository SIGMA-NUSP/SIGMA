package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** CAD_SALA — era cadastro.sala */
@Entity
@Table(name = "CAD_SALA")
@Getter @Setter
public class Sala extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String nome;

    @Column(nullable = false)
    private Boolean ativo = true;

    private Integer ordem;

    @Column(name = "MULTI_OPERADOR", nullable = false)
    private Boolean multiOperador = false;
}
