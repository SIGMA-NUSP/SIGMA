package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * INT_METABASE_DASHBOARD — catálogo de dashboards Metabase exibidos
 * embutidos (static embedding) na página /admin/analise do sistema.
 *
 * Cada linha aponta para um dashboard pré-montado no Metabase via
 * {@link #metabaseDashboardId}. O título/descrição/ícone alimentam
 * o card da sidebar; PARAMS_LOCKED, se preenchido, vira o objeto
 * "params" do JWT de embed (filtros fixos, ex.: {"sala_id": 1}).
 *
 * {@link #contexto} diz a que página a linha pertence, para que páginas
 * diferentes embutam dashboards diferentes lendo o mesmo catálogo.
 */
@Entity
@Table(name = "INT_METABASE_DASHBOARD")
@Getter @Setter
public class MetabaseDashboard extends AuditableEntity {

    /** Dashboard do Painel Administrativo, a página inicial dos admins. */
    public static final String CONTEXTO_PAINEL = "PAINEL";

    /** Indicadores por operador da página de Gestão de Pessoas. */
    public static final String CONTEXTO_GESTAO_PESSOAS = "GESTAO_PESSOAS";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "METABASE_DASHBOARD_ID", nullable = false, unique = true)
    private Integer metabaseDashboardId;

    @Column(name = "TITULO", nullable = false, length = 120)
    private String titulo;

    @Column(name = "DESCRICAO", length = 500)
    private String descricao;

    @Column(name = "ICONE", length = 60)
    private String icone;

    @Column(name = "ORDEM", nullable = false)
    private Integer ordem = 0;

    @Column(name = "ATIVO", nullable = false)
    private Boolean ativo = true;

    @Column(name = "PARAMS_LOCKED", columnDefinition = "CLOB")
    private String paramsLocked;

    @Column(name = "CONTEXTO", nullable = false, length = 30)
    private String contexto = CONTEXTO_PAINEL;
}
