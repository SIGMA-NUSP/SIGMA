package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * PNT_RETIFICACAO — retificação de folha de ponto: 1 linha por (pessoa, dia),
 * vinculada à página da folha oficial PUBLICADA (Q2 — sem folha, sem
 * retificação). Sem edição nem exclusão na v1 (Q1). Horários 'HH:MM' em pares
 * completos 0/2/4 (Q32 — CHECK no banco). Pessoa polimórfica
 * PESSOA_ID/PESSOA_TIPO sem FK, padrão de PNT_LOTE_PAGINA.
 */
@Entity
@Table(name = "PNT_RETIFICACAO")
@Getter @Setter
public class PontoRetificacao extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "PESSOA_ID", nullable = false)
    private String pessoaId;

    /** OPERADOR | TECNICO | ADMINISTRADOR */
    @Column(name = "PESSOA_TIPO", nullable = false)
    private String pessoaTipo;

    /** Página da folha oficial publicada que origina a retificação (proveniência). */
    @Column(name = "PAGINA_ID", nullable = false)
    private String paginaId;

    @Column(name = "DATA", nullable = false)
    private LocalDate data;

    /** 'HH:MM' — pares Ent./Saí. completos: 0, 2 ou 4 horários (Q32). */
    @Column(name = "ENT1")
    private String ent1;

    @Column(name = "SAI1")
    private String sai1;

    @Column(name = "ENT2")
    private String ent2;

    @Column(name = "SAI2")
    private String sai2;

    @Column(name = "OBSERVACOES")
    private String observacoes;
}
