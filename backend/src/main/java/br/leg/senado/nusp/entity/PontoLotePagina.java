package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * PNT_LOTE_PAGINA — uma página do PDF do lote, vinculada a uma pessoa.
 * O vínculo (PESSOA_ID/PESSOA_TIPO) é polimórfico sobre
 * PES_OPERADOR/PES_TECNICO/PES_ADMINISTRADOR (changeset 019 — terceirizados
 * com cargo especial), por isso sem FK (mesmo padrão de PES_AUTH_SESSION).
 */
@Entity
@Table(name = "PNT_LOTE_PAGINA")
@Getter @Setter
public class PontoLotePagina extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "LOTE_ID", nullable = false)
    private String loteId;

    @Column(name = "NUMERO_PAGINA", nullable = false)
    private Integer numeroPagina;

    /** Nome lido da página (melhor esforço) — dica de exibição na revisão. */
    @Column(name = "NOME_EXTRAIDO")
    private String nomeExtraido;

    /** NULL enquanto pendente. */
    @Column(name = "PESSOA_ID")
    private String pessoaId;

    /** OPERADOR | TECNICO | ADMINISTRADOR | null */
    @Column(name = "PESSOA_TIPO")
    private String pessoaTipo;

    /** AUTO | MANUAL | PENDENTE */
    @Column(name = "STATUS_MATCH", nullable = false)
    private String statusMatch = "PENDENTE";

    /** Caminho relativo (sob app.files.dir) do PDF de 1 página. */
    @Column(name = "ARQUIVO_PAGINA", nullable = false)
    private String arquivoPagina;

    /**
     * BANCO final impresso na folha, em minutos com sinal — extraído 1× na
     * publicação do lote (Q5). NULL = ainda não parseado ou parse falhou
     * (WARN, não aborta a publicação).
     */
    @Column(name = "BANCO_FINAL_MIN")
    private Integer bancoFinalMin;
}
