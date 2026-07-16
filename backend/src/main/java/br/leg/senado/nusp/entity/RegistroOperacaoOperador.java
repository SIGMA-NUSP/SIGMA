package br.leg.senado.nusp.entity;

import br.leg.senado.nusp.enums.TipoEvento;
import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/** OPR_REGISTRO_ENTRADA — era operacao.registro_operacao_operador */
@Entity
@Table(name = "OPR_REGISTRO_ENTRADA")
@Getter @Setter
public class RegistroOperacaoOperador extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "REGISTRO_ID", nullable = false)
    private Long registroId;

    @Column(name = "OPERADOR_ID", nullable = false)
    private String operadorId;

    /** Substitui CHECK ck_regopop_ordem_pos (ordem >= 1) */
    @Min(1)
    @Column(nullable = false)
    private Integer ordem;

    /** Substitui CHECK ck_regopop_seq_1_2 (seq IN (1,2)); DEFAULT 1 → Java */
    @Min(1) @Max(2)
    @Column(nullable = false)
    private Integer seq = 1;

    @Column(name = "NOME_EVENTO")
    private String nomeEvento;

    @Column(name = "HORARIO_PAUTA")
    private String horarioPauta;   // HH:MM:SS

    @Column(name = "HORARIO_INICIO")
    private String horarioInicio;  // HH:MM:SS

    @Column(name = "HORARIO_TERMINO")
    private String horarioTermino; // HH:MM:SS

    /** Substitui CHECK ck_regopop_tipo_evento; DEFAULT 'operacao' → Java */
    @Column(name = "TIPO_EVENTO", nullable = false)
    private TipoEvento tipoEvento = TipoEvento.OPERACAO;

    @Column(name = "USB_01")
    private String usb01;

    @Column(name = "USB_02")
    private String usb02;

    @Column(columnDefinition = "CLOB")
    private String observacoes;

    /**
     * Gerenciado por AnormalidadeService.syncHouveAnormalidade().
     * Substitui trigger operacao.sync_houve_anormalidade().
     */
    @Column(name = "HOUVE_ANORMALIDADE")
    private Boolean houveAnormalidade;

    @Column(name = "COMISSAO_ID")
    private Long comissaoId;

    @Column(name = "RESPONSAVEL_EVENTO")
    private String responsavelEvento;

    /** Validação hora_saida > hora_entrada → OperacaoService (substitui CHECK ck_horas_coerentes) */
    @Column(name = "HORA_ENTRADA")
    private String horaEntrada; // HH:MM:SS

    @Column(name = "HORA_SAIDA")
    private String horaSaida;   // HH:MM:SS

    @Column(name = "CRIADO_POR")
    private String criadoPor;

    @Column(name = "ATUALIZADO_POR")
    private String atualizadoPor;

    // Flags de auditoria de edição (DEFAULT false → Java)
    @Column(nullable = false) private Boolean editado = false;
    @Column(name = "OBSERVACOES_EDITADO",         nullable = false) private Boolean observacoesEditado = false;
    @Column(name = "NOME_EVENTO_EDITADO",          nullable = false) private Boolean nomeEventoEditado = false;
    @Column(name = "RESPONSAVEL_EVENTO_EDITADO",   nullable = false) private Boolean responsavelEventoEditado = false;
    @Column(name = "HORARIO_PAUTA_EDITADO",        nullable = false) private Boolean horarioPautaEditado = false;
    @Column(name = "HORARIO_INICIO_EDITADO",       nullable = false) private Boolean horarioInicioEditado = false;
    @Column(name = "HORARIO_TERMINO_EDITADO",      nullable = false) private Boolean horarioTerminoEditado = false;
    @Column(name = "USB_01_EDITADO",               nullable = false) private Boolean usb01Editado = false;
    @Column(name = "USB_02_EDITADO",               nullable = false) private Boolean usb02Editado = false;
    @Column(name = "COMISSAO_EDITADO",             nullable = false) private Boolean comissaoEditado = false;
    @Column(name = "SALA_EDITADO",                 nullable = false) private Boolean salaEditado = false;
    @Column(name = "HORA_ENTRADA_EDITADO",         nullable = false) private Boolean horaEntradaEditado = false;
    @Column(name = "HORA_SAIDA_EDITADO",           nullable = false) private Boolean horaSaidaEditado = false;
    @Column(name = "SUSPENSOES_EDITADO",           nullable = false) private Boolean suspensoesEditado = false;
}
