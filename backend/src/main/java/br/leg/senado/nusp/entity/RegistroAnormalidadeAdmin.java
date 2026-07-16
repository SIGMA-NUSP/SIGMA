package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** OPR_ANORMALIDADE_ADMIN — era operacao.registro_anormalidade_admin (1-para-1 com OPR_ANORMALIDADE) */
@Entity
@Table(name = "OPR_ANORMALIDADE_ADMIN")
@Getter @Setter
public class RegistroAnormalidadeAdmin {

    /** PK é também FK para OPR_ANORMALIDADE — relação 1-para-1 */
    @Id
    @Column(name = "REGISTRO_ANORMALIDADE_ID")
    private Long registroAnormalidadeId;

    @Column(name = "OBSERVACAO_SUPERVISOR", columnDefinition = "CLOB")
    private String observacaoSupervisor;

    @Column(name = "OBSERVACAO_CHEFE", columnDefinition = "CLOB")
    private String observacaoChefe;

    @Column(name = "CRIADO_POR")
    private String criadoPor;

    @Column(name = "CRIADO_EM", nullable = false, updatable = false)
    private Instant criadoEm;

    @Column(name = "ATUALIZADO_POR")
    private String atualizadoPor;

    @Column(name = "ATUALIZADO_EM")
    private Instant atualizadoEm;

    @PrePersist
    protected void onCreate() {
        this.criadoEm = Instant.now();
        this.atualizadoEm = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.atualizadoEm = Instant.now();
    }
}
