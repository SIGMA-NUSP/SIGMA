package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** OPR_REGISTRO_AUDIO — era operacao.registro_operacao_audio */
@Entity
@Table(name = "OPR_REGISTRO_AUDIO")
@Getter @Setter
public class RegistroOperacaoAudio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate data;

    @Column(name = "SALA_ID", nullable = false)
    private Integer salaId;

    @Column(name = "CRIADO_EM", nullable = false, updatable = false)
    private Instant criadoEm;

    @Column(name = "CRIADO_POR")
    private String criadoPor;

    @Column(name = "EM_ABERTO", nullable = false)
    private Boolean emAberto = true;

    @Column(name = "FECHADO_EM")
    private Instant fechadoEm;

    @Column(name = "FECHADO_POR")
    private String fechadoPor;

    @Column(name = "CHECKLIST_DO_DIA_ID")
    private Long checklistDoDiaId;

    @Column(name = "CHECKLIST_DO_DIA_OK")
    private Boolean checklistDoDiaOk;

    @Column(name = "DATA_EDITADO", nullable = false)
    private Boolean dataEditado = false;

    /** Nome livre da sala digitado pelo operador quando SALA_ID = "Demais Salas". */
    @Column(name = "NOME_DEMAIS_SALAS", length = 255)
    private String nomeDemaisSalas;

    @PrePersist
    protected void onCreate() {
        this.criadoEm = Instant.now();
    }
}
