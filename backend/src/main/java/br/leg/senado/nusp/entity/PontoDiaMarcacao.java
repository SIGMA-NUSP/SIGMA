package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * PNT_DIA_MARCACAO — marcação global de dia do ponto: um tipo de escopo GLOBAL
 * do catálogo ({@link PontoTipoMarcacao}) valendo para todos os funcionários.
 * 1 marcação por dia (UK); trocar tipo = update, desmarcar = delete físico (é
 * configuração, não fato auditável além do CRIADO_POR_ID).
 */
@Entity
@Table(name = "PNT_DIA_MARCACAO")
@Getter @Setter
public class PontoDiaMarcacao extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "DATA", nullable = false)
    private LocalDate data;

    /** Tipo do catálogo (escopo GLOBAL) — a FK não tem cascade: a exclusão do tipo é explícita. */
    @Column(name = "TIPO_ID", nullable = false)
    private String tipoId;

    @Column(name = "CRIADO_POR_ID", nullable = false)
    private String criadoPorId;
}
