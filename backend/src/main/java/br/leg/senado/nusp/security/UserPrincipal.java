package br.leg.senado.nusp.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Representa o usuário autenticado dentro do Spring Security.
 * Equivale ao request.auth_user do Python.
 */
@Getter
public class UserPrincipal implements UserDetails {

    private final String id;
    private final String role;      // "operador" ou "administrador"
    private final String username;
    private final String name;
    private final String email;
    private final Long sid;
    private final long exp;

    public UserPrincipal(String id, String role, String username,
                         String name, String email, Long sid, long exp) {
        this.id = id;
        this.role = role;
        this.username = username;
        this.name = name;
        this.email = email;
        this.sid = sid;
        this.exp = exp;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
    }

    @Override public String getPassword()         { return null; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked()  { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled()           { return true; }
}
