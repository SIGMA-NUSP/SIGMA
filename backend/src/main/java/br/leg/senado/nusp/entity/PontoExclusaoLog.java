package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * PNT_EXCLUSAO_LOG — trilha da exclusão de um lote de folhas de ponto (ou de
 * uma folha dele) pelo admin master (F59): quem excluiu, quando, e o quê.
 *
 * <p>O "o quê" é um SNAPSHOT ({@code RESUMO}, JSON com tipo, período, pessoas e
 * as contagens REAIS do que morreu), nunca uma referência: as linhas apontadas
 * já não existem quando a trilha for lida. Por isso {@code LOTE_ID}/{@code PAGINA_ID}
 * são ids SEM FK — proveniência, no idioma de {@code PNT_BANCO_SALDO.ANCORA_PAGINA_ID}.
 */
@Entity
@Table(name = "PNT_EXCLUSAO_LOG")
@Getter @Setter
public class PontoExclusaoLog extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** LOTE | PAGINA */
    @Column(name = "ESCOPO", nullable = false)
    private String escopo;

    /** Id do lote excluído (ou do lote da página) — sem FK: a linha morreu. */
    @Column(name = "LOTE_ID", nullable = false)
    private String loteId;

    /** Id da página excluída; NULL quando o escopo é o lote inteiro. */
    @Column(name = "PAGINA_ID")
    private String paginaId;

    @Column(name = "EXCLUIDO_POR_ID", nullable = false)
    private String excluidoPorId;

    @Column(name = "EXCLUIDO_EM", nullable = false)
    private LocalDateTime excluidoEm;

    /** JSON descritivo do que foi apagado (contagens reais da transação, não as do preview). */
    @Lob
    @Column(name = "RESUMO", nullable = false)
    private String resumo;
}
