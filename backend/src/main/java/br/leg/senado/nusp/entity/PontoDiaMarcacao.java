package br.leg.senado.nusp.entity;

import br.leg.senado.nusp.enums.TipoDiaMarcacao;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * PNT_DIA_MARCACAO — marcação global de dia do ponto (Q6): FERIADO ou
 * PONTO_FACULTATIVO para todos os funcionários. 1 marcação por dia (UK);
 * trocar tipo = update, desmarcar = delete físico (é configuração, não fato
 * auditável além do CRIADO_POR_ID).
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

    @Column(name = "TIPO", nullable = false)
    private TipoDiaMarcacao tipo;

    @Column(name = "CRIADO_POR_ID", nullable = false)
    private String criadoPorId;
}
