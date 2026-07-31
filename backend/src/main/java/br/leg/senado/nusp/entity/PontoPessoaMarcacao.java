package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * PNT_PESSOA_MARCACAO — marcação por pessoa-dia do ponto: um tipo de escopo
 * INDIVIDUAL do catálogo ({@link PontoTipoMarcacao}) valendo para um
 * funcionário num dia. 1 por (pessoa, dia) (UK); trocar tipo = update,
 * desmarcar = delete físico, como na marcação global. Pessoa polimórfica sem
 * FK, padrão de PNT_LOTE_PAGINA.
 */
@Entity
@Table(name = "PNT_PESSOA_MARCACAO")
@Getter @Setter
public class PontoPessoaMarcacao extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "PESSOA_ID", nullable = false)
    private String pessoaId;

    /** OPERADOR | TECNICO | ADMINISTRADOR */
    @Column(name = "PESSOA_TIPO", nullable = false)
    private String pessoaTipo;

    @Column(name = "DATA", nullable = false)
    private LocalDate data;

    /** Tipo do catálogo (escopo INDIVIDUAL) — a FK não tem cascade: a exclusão do tipo é explícita. */
    @Column(name = "TIPO_ID", nullable = false)
    private String tipoId;

    @Column(name = "CRIADO_POR_ID", nullable = false)
    private String criadoPorId;
}
