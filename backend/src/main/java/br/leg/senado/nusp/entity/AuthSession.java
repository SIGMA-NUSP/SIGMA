package br.leg.senado.nusp.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** PES_AUTH_SESSION — era pessoa.auth_sessions */
@Entity
@Table(name = "PES_AUTH_SESSION")
@Getter @Setter
public class AuthSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "USER_ID", nullable = false)
    private String userId;

    @Column(name = "REFRESH_TOKEN_HASH", nullable = false)
    private String refreshTokenHash;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "LAST_ACTIVITY", nullable = false)
    private Instant lastActivity;

    @Column(nullable = false)
    private Boolean revoked = false;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        this.lastActivity = Instant.now();
    }
}
