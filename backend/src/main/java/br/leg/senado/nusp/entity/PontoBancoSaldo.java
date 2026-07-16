package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * PNT_BANCO_SALDO — saldo do banco de horas: 1 linha por pessoa (handoff
 * §3.1). Âncora oficial + cache: SALDO_ABERTURA_MIN vem do BANCO impresso na
 * folha publicada mais recente; SALDO_BANCO_MIN é recalculado nos eventos
 * (publicar lote, criar/deliberar/cancelar solicitação) como
 * abertura − Σ débitos vivos com DATA_FOLGA &gt; ANCORA_DATA (Q4).
 * Pessoa sem folha oficial: abertura 0 e âncora NULL ("sem folha oficial").
 * Minutos sempre com sinal. Pessoa polimórfica sem FK.
 */
@Entity
@Table(name = "PNT_BANCO_SALDO")
@Getter @Setter
public class PontoBancoSaldo extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "PESSOA_ID", nullable = false)
    private String pessoaId;

    /** OPERADOR | TECNICO | ADMINISTRADOR */
    @Column(name = "PESSOA_TIPO", nullable = false)
    private String pessoaTipo;

    /** Minutos com sinal, derivados do PDF oficial (0 se pessoa sem folha). */
    @Column(name = "SALDO_ABERTURA_MIN", nullable = false)
    private Integer saldoAberturaMin;

    /** Cache: abertura − Σ débitos vivos pós-âncora. */
    @Column(name = "SALDO_BANCO_MIN", nullable = false)
    private Integer saldoBancoMin;

    /** DATA_FIM do lote da folha que originou a abertura (até onde o oficial cobre). */
    @Column(name = "ANCORA_DATA")
    private LocalDate ancoraData;

    /** Página oficial parseada (proveniência, sem FK); NULL se não há folha. */
    @Column(name = "ANCORA_PAGINA_ID")
    private String ancoraPaginaId;
}
