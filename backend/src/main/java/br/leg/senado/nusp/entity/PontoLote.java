package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * PNT_LOTE — um upload de cartão-ponto (PDF multi-página) feito pelo admin.
 * Cada upload é permanente; nada é substituído.
 */
@Entity
@Table(name = "PNT_LOTE")
@Getter @Setter
public class PontoLote extends AuditableEntity {

    /** Folha mensal que abre o mês: retificável e substituível pela definitiva. */
    public static final String CATEGORIA_PREVIA = "PREVIA";
    /** Folha mensal que fecha o mês: entra por cima da prévia e não se retifica. */
    public static final String CATEGORIA_DEFINITIVA = "DEFINITIVA";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** SEMANAL | MENSAL */
    @Column(name = "TIPO", nullable = false)
    private String tipo;

    /** PREVIA | DEFINITIVA na folha mensal; sempre nulo na semanal, que não tem essa distinção. */
    @Column(name = "CATEGORIA")
    private String categoria;

    @Column(name = "DATA_INICIO", nullable = false)
    private LocalDate dataInicio;

    @Column(name = "DATA_FIM", nullable = false)
    private LocalDate dataFim;

    /** Caminho relativo (sob app.files.dir) do PDF original. */
    @Column(name = "ARQUIVO_ORIGINAL", nullable = false)
    private String arquivoOriginal;

    @Column(name = "TOTAL_PAGINAS", nullable = false)
    private Integer totalPaginas;

    /** REVISAO | PUBLICADO */
    @Column(name = "STATUS", nullable = false)
    private String status = "REVISAO";

    @Column(name = "CRIADO_POR_ID", nullable = false)
    private String criadoPorId;

    @Column(name = "PUBLICADO_EM")
    private LocalDateTime publicadoEm;
}
