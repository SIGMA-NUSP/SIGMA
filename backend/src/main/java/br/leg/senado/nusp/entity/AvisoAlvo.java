package br.leg.senado.nusp.entity;

import br.leg.senado.nusp.enums.AlvoTipoAviso;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * FRM_AVISO_ALVO — público de um cadastro de aviso. Cada linha é um destino:
 * uma sala, um operador, um técnico, um administrador, ou um coletivo (TODOS_*).
 * Apenas a FK coerente com ALVO_TIPO é preenchida (CHECK no banco).
 */
@Entity
@Table(name = "FRM_AVISO_ALVO")
@Getter @Setter
public class AvisoAlvo extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "CADASTRO_ID", nullable = false)
    private String cadastroId;

    @Column(name = "ALVO_TIPO", nullable = false)
    private AlvoTipoAviso alvoTipo;

    @Column(name = "SALA_ID")
    private Integer salaId;

    @Column(name = "OPERADOR_ID")
    private String operadorId;

    @Column(name = "TECNICO_ID")
    private String tecnicoId;

    @Column(name = "ADMIN_ID")
    private String adminId;
}
