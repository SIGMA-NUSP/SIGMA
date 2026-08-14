package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * PNT_RETIFICACAO — retificação de folha de ponto: 1 linha por (pessoa, dia),
 * vinculada à página da folha oficial publicada que a originou — sem folha, sem
 * retificação.
 *
 * <p>O dia guarda <b>ou</b> horários 'HH:MM' corrigidos, um campo de cada vez e
 * independentes entre si (o que fica nulo continua valendo o que veio na folha),
 * <b>ou</b> um tipo de ocorrência declarado para o dia inteiro. Nunca os dois, e
 * nunca nenhum: a retificação sem conteúdo não existe — apagá-la é apagar a
 * linha (CHECK no banco).
 *
 * <p>Pessoa polimórfica PESSOA_ID/PESSOA_TIPO sem FK, padrão de PNT_LOTE_PAGINA.
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

    /** 'HH:MM' corrigido pelo funcionário; nulo = vale o horário da folha. */
    @Column(name = "ENT1")
    private String ent1;

    @Column(name = "SAI1")
    private String sai1;

    @Column(name = "ENT2")
    private String ent2;

    @Column(name = "SAI2")
    private String sai2;

    /**
     * Tipo de ocorrência do catálogo declarado para o dia inteiro — a FK não tem
     * cascade: a exclusão do tipo é explícita. Nulo na retificação de horários.
     */
    @Column(name = "TIPO_ID")
    private String tipoId;

    @Column(name = "OBSERVACOES")
    private String observacoes;
}
