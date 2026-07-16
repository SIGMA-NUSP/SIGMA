package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** OPR_REGISTRO_ENTRADA_HIST — era operacao.registro_operacao_operador_historico (snapshot imutável) */
@Entity
@Table(name = "OPR_REGISTRO_ENTRADA_HIST")
@Getter @Setter
public class RegistroOperacaoOperadorHistorico {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ENTRADA_ID", nullable = false)
    private Long entradaId;

    @Column(nullable = false, columnDefinition = "CLOB")
    private String snapshot;  // jsonb → CLOB; serializado via Jackson

    @Column(name = "EDITADO_POR")
    private String editadoPor;

    @Column(name = "EDITADO_EM", nullable = false, updatable = false)
    private Instant editadoEm;

    @PrePersist
    protected void onCreate() {
        this.editadoEm = Instant.now();
    }
}
