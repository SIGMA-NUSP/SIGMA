package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * PNT_TIPO_MARC_EXCLUSAO_LOG — trilha da exclusão de um tipo de ocorrência.
 *
 * <p>Excluir um tipo apaga junto todas as marcações feitas com ele, e nada disso
 * volta: recriar um tipo de mesmo nome depois não religa marcação nenhuma. Por
 * isso o registro é um SNAPSHOT — nome, badge e escopo copiados, mais o RESUMO
 * em JSON com as contagens reais da transação — e o TIPO_ID não tem FK: a linha
 * que ele aponta acabou de morrer.
 */
@Entity
@Table(name = "PNT_TIPO_MARC_EXCLUSAO_LOG")
@Getter @Setter
public class PontoTipoMarcacaoExclusaoLog extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "TIPO_ID", nullable = false)
    private String tipoId;

    @Column(name = "NOME", nullable = false, length = 20)
    private String nome;

    @Column(name = "BADGE", nullable = false, length = 3)
    private String badge;

    /** GLOBAL | INDIVIDUAL */
    @Column(name = "ESCOPO", nullable = false, length = 10)
    private String escopo;

    @Column(name = "EXCLUIDO_POR_ID", nullable = false)
    private String excluidoPorId;

    @Column(name = "EXCLUIDO_EM", nullable = false)
    private LocalDateTime excluidoEm;

    /** Snapshot JSON do que morreu com o tipo. */
    @Lob
    @Column(name = "RESUMO", nullable = false)
    private String resumo;
}
