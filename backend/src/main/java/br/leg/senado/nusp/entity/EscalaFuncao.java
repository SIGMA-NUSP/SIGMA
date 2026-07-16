package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** OPR_ESCALA_FUNCAO — vínculo operador ↔ função especial numa escala (Apoio às Comissões, Fechamento, etc.) */
@Entity
@Table(name = "OPR_ESCALA_FUNCAO")
@Getter @Setter
public class EscalaFuncao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ESCALA_ID", nullable = false)
    private Long escalaId;

    /** Tipo da função (ex.: APOIO_COMISSOES, FECHAMENTO). */
    @Column(name = "TIPO", nullable = false, length = 40)
    private String tipo;

    @Column(name = "OPERADOR_ID", nullable = false)
    private String operadorId;
}
