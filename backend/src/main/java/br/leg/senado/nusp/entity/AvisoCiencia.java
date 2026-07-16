package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * FRM_AVISO_CIENCIA — ciência de um cadastro por um operador, um técnico OU
 * um administrador (exclusivos, via CHECK). Substitui FRM_AVISO_SALA_CIENCIA.
 * Vazia para tipos que não exigem ciência.
 */
@Entity
@Table(name = "FRM_AVISO_CIENCIA")
@Getter @Setter
public class AvisoCiencia extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "CADASTRO_ID", nullable = false)
    private String cadastroId;

    /** Sala da verificação em que a ciência foi dada (avisos de sala). Nulo para tipos sem sala. */
    @Column(name = "SALA_ID")
    private Integer salaId;

    @Column(name = "OPERADOR_ID")
    private String operadorId;

    @Column(name = "TECNICO_ID")
    private String tecnicoId;

    @Column(name = "ADMIN_ID")
    private String adminId;

    @Column(name = "CIENTE_EM", nullable = false)
    private LocalDateTime cienteEm;
}
