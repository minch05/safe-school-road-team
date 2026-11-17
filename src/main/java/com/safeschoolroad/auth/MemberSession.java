package com.safeschoolroad.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.filter.OncePerRequestFilter;

final class MemberSession extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;

    MemberSession(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static final class Principal extends User {
        private final long id;
        private final byte[] credentialVersion;

        Principal(long id, String username, String password) {
            super(username, password, AuthorityUtils.createAuthorityList("ROLE_USER"));
            this.id = id;
            this.credentialVersion = fingerprint(password);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Principal member
                && jdbc.queryForList("SELECT password_hash FROM member WHERE id=? AND login_id=?",
                        String.class, member.id, member.getUsername()).stream()
                   .noneMatch(hash -> MessageDigest.isEqual(member.credentialVersion, fingerprint(hash)))) {
            var session = request.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }

    private static byte[] fingerprint(String password) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
