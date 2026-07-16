package br.leg.senado.nusp.entity;

import br.leg.senado.nusp.enums.TipoWidget;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** FRM_CHECKLIST_ITEM_TIPO — era forms.checklist_item_tipo */
@Entity
@Table(name = "FRM_CHECKLIST_ITEM_TIPO")
@Getter @Setter
public class ChecklistItemTipo extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String nome;

    /** Substitui CHECK checklist_item_tipo_tipo_widget_check; DEFAULT 'radio' → Java */
    @Column(name = "TIPO_WIDGET", nullable = false)
    private TipoWidget tipoWidget = TipoWidget.RADIO;
}
