package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * PNT_REGISTRO_MANUAL — batidas manuais + fotos de comprovante: 1 linha por
 * (pessoa, dia) (handoff §3.2). Schema/entidade já criados (Q9), mas a
 * feature fica OCULTA na v1 (card sob flag — P3/T-2.1). Horas 'HH:MM'
 * VARCHAR2(5) (Q8). TOTAL_DIA_MIN é derivado pelo motor de cálculo (penhasco
 * ±5/±9, ×2/×1; dia sem par completo = 0); a jornada vem de
 * PES_*.CARGA_HORARIA, nunca daqui. Fotos = caminho relativo sob
 * app.files.dir ('ponto/comprovantes/&lt;uuid&gt;'), 1 por batida.
 */
@Entity
@Table(name = "PNT_REGISTRO_MANUAL")
@Getter @Setter
public class PontoRegistroManual extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "PESSOA_ID", nullable = false)
    private String pessoaId;

    /** OPERADOR | TECNICO | ADMINISTRADOR */
    @Column(name = "PESSOA_TIPO", nullable = false)
    private String pessoaTipo;

    @Column(name = "DATA", nullable = false)
    private LocalDate data;

    /** 'HH:MM' — batidas do dia; par incompleto vale 0 no motor, não é erro. */
    @Column(name = "ENT1")
    private String ent1;

    @Column(name = "SAI1")
    private String sai1;

    @Column(name = "ENT2")
    private String ent2;

    @Column(name = "SAI2")
    private String sai2;

    /** Minutos com sinal computados pelo motor (0 = dia neutro/vazio). */
    @Column(name = "TOTAL_DIA_MIN", nullable = false)
    private Integer totalDiaMin = 0;

    @Column(name = "FOTO_ENT1")
    private String fotoEnt1;

    @Column(name = "FOTO_SAI1")
    private String fotoSai1;

    @Column(name = "FOTO_ENT2")
    private String fotoEnt2;

    @Column(name = "FOTO_SAI2")
    private String fotoSai2;
}
