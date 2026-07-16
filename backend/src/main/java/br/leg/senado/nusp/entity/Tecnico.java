package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** PES_TECNICO — terceiro tipo de usuário (além de operador e administrador) */
@Entity
@Table(name = "PES_TECNICO")
@Getter @Setter
public class Tecnico extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "NOME_COMPLETO", nullable = false)
    private String nomeCompleto;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "PASSWORD_HASH", nullable = false)
    private String passwordHash;

    @Column(name = "FOTO_URL")
    private String fotoUrl;

    /** Turno: 'M' (Matutino) ou 'V' (Vespertino); NULL = não definido */
    @Column(name = "TURNO", length = 1)
    private String turno;

    /** Carga horária semanal: 30 ou 40 (NULL = não definida) */
    @Column(name = "CARGA_HORARIA")
    private Integer cargaHoraria;

    /** Início do horário de trabalho, formato 'HH:MM' (NULL = não definido) */
    @Column(name = "HORARIO_TRABALHO_INICIO", length = 5)
    private String horarioTrabalhoInicio;

    /** Fim do horário de trabalho, formato 'HH:MM' (NULL = não definido) */
    @Column(name = "HORARIO_TRABALHO_FIM", length = 5)
    private String horarioTrabalhoFim;

    /** Substitui extensão citext — armazena sempre em lowercase */
    public void setEmail(String email) {
        this.email = email != null ? email.toLowerCase() : null;
    }

    /** Substitui extensão citext — armazena sempre em lowercase */
    public void setUsername(String username) {
        this.username = username != null ? username.toLowerCase() : null;
    }
}
