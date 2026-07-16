package br.leg.senado.nusp.entity;

import br.leg.senado.nusp.enums.StatusSolicitacaoFolga;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * PNT_SOLICITACAO_FOLGA — livro-razão dos débitos do banco de horas: 1 linha
 * por dia solicitado. MINUTOS_DEBITADOS congela 360/480 na criação (Q3);
 * rejeição/cancelamento não são estorno — a linha sai da soma do saldo
 * (débitos vivos = PENDENTE/APROVADO com DATA_FOLGA posterior à âncora, Q4).
 * A FBI UQ_PNT_SOLF_VIVA (changeset 036) impede 2 pedidos vivos para o mesmo
 * (pessoa, dia). Pessoa polimórfica sem FK, padrão de PNT_LOTE_PAGINA.
 */
@Entity
@Table(name = "PNT_SOLICITACAO_FOLGA")
@Getter @Setter
public class PontoSolicitacaoFolga extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "PESSOA_ID", nullable = false)
    private String pessoaId;

    /** OPERADOR | TECNICO | ADMINISTRADOR */
    @Column(name = "PESSOA_TIPO", nullable = false)
    private String pessoaTipo;

    @Column(name = "DATA_FOLGA", nullable = false)
    private LocalDate dataFolga;

    /** Débito congelado na criação: 360 (carga 30) ou 480 (carga 40) — Q3. */
    @Column(name = "MINUTOS_DEBITADOS", nullable = false)
    private Integer minutosDebitados;

    @Column(name = "STATUS", nullable = false)
    private StatusSolicitacaoFolga status = StatusSolicitacaoFolga.PENDENTE;

    /** Admin que deliberou — obrigatório em APROVADO/REJEITADO, NULL nos demais (CHECK). */
    @Column(name = "DELIBERADO_POR_ID")
    private String deliberadoPorId;

    @Column(name = "DELIBERADO_EM")
    private LocalDateTime deliberadoEm;

    @Column(name = "MOTIVO_REJEICAO")
    private String motivoRejeicao;
}
