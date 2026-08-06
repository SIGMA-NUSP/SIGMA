package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * PNT_FOLHA_LINHA — uma linha-dia da folha de ponto publicada, como o PDF a imprime.
 *
 * <p>As 7 colunas do cartão Secullum são cópia VERBATIM do que está impresso; a célula em branco
 * na folha vira nulo aqui (o Oracle não distingue string vazia de nulo). {@code data} e
 * {@code ocorrencia} são derivadas do verbatim para as consultas, e ficam nulas quando a linha não
 * as fornece — a linha é gravada de todo jeito.
 */
@Entity
@Table(name = "PNT_FOLHA_LINHA")
@Getter @Setter
public class PontoFolhaLinha extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "PAGINA_ID", nullable = false)
    private String paginaId;

    /** Posição da linha na página — é ela que identifica a linha, não a data. */
    @Column(name = "ORDEM", nullable = false)
    private Integer ordem;

    /** Coluna DIA como impressa: "dd/mm/aa - seg". */
    @Column(name = "DIA_VERBATIM", length = 30)
    private String diaVerbatim;

    @Column(name = "ENT1", length = 20)
    private String ent1;

    @Column(name = "SAI1", length = 20)
    private String sai1;

    @Column(name = "ENT2", length = 20)
    private String ent2;

    @Column(name = "SAI2", length = 20)
    private String sai2;

    /** TOTALDIA com sinal, como impresso: "+01:24". */
    @Column(name = "TOTAL_DIA", length = 12)
    private String totalDia;

    /** BANCO acumulado com sinal, como impresso: "-102:05". */
    @Column(name = "BANCO", length = 12)
    private String banco;

    /** Data-calendário do dia, derivada do verbatim. */
    @Column(name = "DATA")
    private LocalDate data;

    /** Status do dia (FERNC, DISPOSI, BancN…); nulo no dia de batidas. */
    @Column(name = "OCORRENCIA", length = 30)
    private String ocorrencia;
}
