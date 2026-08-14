package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * PNT_TIPO_MARCACAO — tipo de ocorrência do ponto (Feriado, Férias, Atestado…),
 * cadastrado pelo admin master. O tipo não entra no cálculo do saldo: o efeito
 * dele é rotular a célula do dia na grade/XLSX e impedir a solicitação de folga
 * naquele dia.
 *
 * <p>O ESCOPO diz quem o tipo alcança: {@code GLOBAL} vale para todos os
 * funcionários (uma marcação por dia); {@code INDIVIDUAL} vale para um
 * funcionário num dia.
 *
 * <p>Nome e badge são únicos em todo o catálogo pela forma normalizada
 * ({@code NOME_NORM}/{@code BADGE_NORM}: maiúsculas, sem acentos, espaços
 * colapsados) — o texto original é exibido exatamente como foi digitado.
 */
@Entity
@Table(name = "PNT_TIPO_MARCACAO")
@Getter @Setter
public class PontoTipoMarcacao extends AuditableEntity {

    /** Vale para todos os funcionários (PNT_DIA_MARCACAO). */
    public static final String ESCOPO_GLOBAL = "GLOBAL";
    /** Vale para um funcionário num dia (PNT_PESSOA_MARCACAO). */
    public static final String ESCOPO_INDIVIDUAL = "INDIVIDUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "NOME", nullable = false, length = 20)
    private String nome;

    @Column(name = "NOME_NORM", nullable = false, length = 20)
    private String nomeNorm;

    /** Rótulo curto exibido sob o número do dia no calendário. */
    @Column(name = "BADGE", nullable = false, length = 3)
    private String badge;

    @Column(name = "BADGE_NORM", nullable = false, length = 3)
    private String badgeNorm;

    /** GLOBAL | INDIVIDUAL */
    @Column(name = "ESCOPO", nullable = false, length = 10)
    private String escopo;

    /** O tipo aparece na lista que o funcionário escolhe ao retificar o dia. */
    @Column(name = "VISIVEL_FUNCIONARIO", nullable = false)
    private Boolean visivelFuncionario = false;

    /**
     * A retificação com este tipo vale como folga do banco de horas: a célula do
     * dia fica igual à da folga aprovada e o dia entra na contagem de folgas.
     * O saldo não é tocado — ele continua vindo só das folhas.
     */
    @Column(name = "CONTA_FOLGA", nullable = false)
    private Boolean contaFolga = false;

    /** Nulo nos tipos que nascem com o sistema — não houve admin que os criasse. */
    @Column(name = "CRIADO_POR_ID")
    private String criadoPorId;
}
