package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** PES_PASSWORD_RESET — tokens de recuperação de senha */
@Entity
@Table(name = "PES_PASSWORD_RESET")
@Getter @Setter
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "USER_ID", nullable = false)
    private String userId;

    @Column(name = "USER_TYPE", nullable = false)
    private String userType;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(name = "EXPIRES_AT", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private Boolean used = false;
}
