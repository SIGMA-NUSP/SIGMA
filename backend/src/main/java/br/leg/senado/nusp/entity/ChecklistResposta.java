package br.leg.senado.nusp.entity;

import br.leg.senado.nusp.enums.StatusResposta;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** FRM_CHECKLIST_RESPOSTA — era forms.checklist_resposta */
@Entity
@Table(name = "FRM_CHECKLIST_RESPOSTA")
@Getter @Setter
public class ChecklistResposta extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "CHECKLIST_ID", nullable = false)
    private Long checklistId;

    @Column(name = "ITEM_TIPO_ID", nullable = false)
    private Integer itemTipoId;

    /** Substitui CHECK ck_cli_resp_status ('Ok', 'Falha') */
    @Column(nullable = false)
    private StatusResposta status;

    /**
     * Obrigatório quando status = FALHA.
     * Substitui CHECK ck_cli_resp_desc_quando_falha → validação no ChecklistService.
     */
    @Column(name = "DESCRICAO_FALHA", columnDefinition = "CLOB")
    private String descricaoFalha;

    @Column(name = "VALOR_TEXTO")
    private String valorTexto;

    @Column(name = "CRIADO_POR")
    private String criadoPor;

    @Column(name = "ATUALIZADO_POR")
    private String atualizadoPor;

    @Column(nullable = false)
    private Boolean editado = false;
}
