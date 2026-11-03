package com.safeschoolroad.auth;

import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthApi {
    private static final Pattern LOGIN_ID = Pattern.compile("[a-z0-9_-]{4,30}");
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public AuthApi(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    public record Signup(String loginId, String password, String nickname, Boolean age14OrOlder) { }

    @GetMapping("/csrf") Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @GetMapping("/me") Map<String, Object> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
            return Map.of("authenticated", false);
        }
        return Map.of("authenticated", true, "loginId", authentication.getName());
    }

    @PostMapping("/signup") ResponseEntity<Map<String, String>> signup(@RequestBody Signup body) {
        if (body == null || body.loginId() == null || !LOGIN_ID.matcher(body.loginId()).matches()
                || body.password() == null || body.password().length() < 10 || body.password().length() > 72
                || body.nickname() == null || body.nickname().isBlank() || body.nickname().length() > 60
                || !Boolean.TRUE.equals(body.age14OrOlder())) {
            return ResponseEntity.badRequest().body(Map.of("error", "가입 정보 또는 만 14세 이상 조건을 확인해 주세요."));
        }
        try {
            jdbc.update("INSERT INTO member (login_id, password_hash, nickname, age_14_or_older) VALUES (?, ?, ?, ?)",
                    body.loginId(), encoder.encode(body.password()), body.nickname().strip(), true);
        } catch (DuplicateKeyException duplicate) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "이미 사용 중인 아이디입니다."));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("loginId", body.loginId()));
    }
}
