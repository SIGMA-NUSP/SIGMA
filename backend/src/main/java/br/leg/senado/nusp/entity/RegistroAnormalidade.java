package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** OPR_ANORMALIDADE — era operacao.registro_anormalidade */
@Entity
@Table(name = "OPR_ANORMALIDADE")
@Getter @Setter
public class RegistroAnormalidade extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "REGISTRO_ID", nullable = false)
    private Long registroId;

    @Column(name = "ENTRADA_ID")
    private Long entradaId;

    @Column(nullable = false)
    private LocalDate data;

    @Column(name = "SALA_ID", nullable = false)
    private Integer salaId;

    @Column(name = "NOME_EVENTO", nullable = false)
    private String nomeEvento;

    @Column(name = "HORA_INICIO_ANORMALIDADE", nullable = false)
    private String horaInicioAnormalidade;  // HH:MM:SS

    @Column(name = "DESCRICAO_ANORMALIDADE", nullable = false, columnDefinition = "CLOB")
    private String descricaoAnormalidade;

    @Column(name = "HOUVE_PREJUIZO", nullable = false)
    private Boolean houvePrejuizo;

    /**
     * Obrigatório se houvePrejuizo = true.
     * Substitui CHECK ck_prejuizo_desc → validação no AnormalidadeService.
     */
    @Column(name = "DESCRICAO_PREJUIZO", columnDefinition = "CLOB")
    private String descricaoPrejuizo;

    @Column(name = "HOUVE_RECLAMACAO", nullable = false)
    private Boolean houveReclamacao;

    /**
     * Obrigatório se houveReclamacao = true.
     * Substitui CHECK ck_reclamacao_desc → validação no AnormalidadeService.
     */
    @Column(name = "AUTORES_CONTEUDO_RECLAMACAO", columnDefinition = "CLOB")
    private String autoresConteudoReclamacao;

    @Column(name = "ACIONOU_MANUTENCAO", nullable = false)
    private Boolean acionouManutencao;

    /**
     * Obrigatório se acionouManutencao = true.
     * Substitui CHECK ck_manutencao_hora → validação no AnormalidadeService.
     */
    @Column(name = "HORA_ACIONAMENTO_MANUTENCAO")
    private String horaAcionamentoManutencao; // HH:MM:SS

    @Column(name = "RESOLVIDA_PELO_OPERADOR", nullable = false)
    private Boolean resolvidaPeloOperador;

    @Column(name = "PROCEDIMENTOS_ADOTADOS", columnDefinition = "CLOB")
    private String procedimentosAdotados;

    /**
     * Deve ser >= data.
     * Substitui CHECK ck_datas_coerentes → validação no AnormalidadeService.
     */
    @Column(name = "DATA_SOLUCAO")
    private LocalDate dataSolucao;

    @Column(name = "HORA_SOLUCAO")
    private String horaSolucao;  // HH:MM:SS

    @Column(name = "RESPONSAVEL_EVENTO", nullable = false)
    private String responsavelEvento;

    @Column(name = "CRIADO_POR", nullable = false)
    private String criadoPor;

    @Column(name = "ATUALIZADO_POR")
    private String atualizadoPor;
}
