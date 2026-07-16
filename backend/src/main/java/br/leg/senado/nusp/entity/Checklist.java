package br.leg.senado.nusp.entity;

import br.leg.senado.nusp.enums.Turno;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** FRM_CHECKLIST — era forms.checklist */
@Entity
@Table(name = "FRM_CHECKLIST")
@Getter @Setter
public class Checklist extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "DATA_OPERACAO", nullable = false)
    private LocalDate dataOperacao;

    @Column(name = "SALA_ID", nullable = false)
    private Integer salaId;

    /** Substitui CHECK ck_checklist_turno ('Matutino', 'Vespertino') */
    @Column(nullable = false)
    private Turno turno;

    @Column(name = "HORA_INICIO_TESTES", nullable = false)
    private String horaInicioTestes;   // HH:MM:SS

    @Column(name = "HORA_TERMINO_TESTES", nullable = false)
    private String horaTerminoTestes;  // HH:MM:SS

    @Column(columnDefinition = "CLOB")
    private String observacoes;

    @Column(name = "USB_01")
    private String usb01;

    @Column(name = "USB_02")
    private String usb02;

    @Column(name = "CRIADO_POR")
    private String criadoPor;

    @Column(name = "ATUALIZADO_POR")
    private String atualizadoPor;

    @Column(nullable = false)
    private Boolean editado = false;

    @Column(name = "OBSERVACOES_EDITADO", nullable = false)
    private Boolean observacoesEditado = false;
}
