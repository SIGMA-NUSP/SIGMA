package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** CAD_COMISSAO — era cadastro.comissao */
@Entity
@Table(name = "CAD_COMISSAO")
@Getter @Setter
public class Comissao extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nome;

    @Column(nullable = false)
    private Boolean ativo = true;

    private Integer ordem;

    @Column(name = "CRIADO_POR")
    private String criadoPor;

    @Column(name = "ATUALIZADO_POR")
    private String atualizadoPor;
}
