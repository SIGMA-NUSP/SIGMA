package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** FRM_AVISO_MENSAGEM — uma das 1..10 mensagens de um cadastro de aviso. */
@Entity
@Table(name = "FRM_AVISO_MENSAGEM")
@Getter @Setter
public class AvisoMensagem extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "CADASTRO_ID", nullable = false)
    private String cadastroId;

    /** 1, 2, 3... — ordem de exibição (label "Xº Aviso"). */
    @Column(name = "ORDEM", nullable = false)
    private Integer ordem;

    @Column(name = "TEXTO", nullable = false, columnDefinition = "CLOB")
    private String texto;
}
