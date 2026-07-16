package br.leg.senado.nusp.entity;

import br.leg.senado.nusp.enums.TipoPessoaMarcacao;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * PNT_PESSOA_MARCACAO — marcação por pessoa-dia do ponto (Q7/F#4):
 * à disposição, atestado, férias, recesso, licença médica — as marcações da
 * planilha real. 1 por (pessoa, dia) (UK); trocar tipo = update, desmarcar =
 * delete físico, como na marcação global. Pessoa polimórfica sem FK, padrão
 * de PNT_LOTE_PAGINA.
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

    @Column(name = "TIPO", nullable = false)
    private TipoPessoaMarcacao tipo;

    @Column(name = "CRIADO_POR_ID", nullable = false)
    private String criadoPorId;
}
