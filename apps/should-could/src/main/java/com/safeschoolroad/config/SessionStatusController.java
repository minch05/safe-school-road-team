package com.safeschoolroad.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import java.util.Map;

/** CSRF 쿠키 갱신용 읽기 요청. 회원 정보·위치·토큰 본문을 반환하지 않는다. */
@RestController
public class SessionStatusController {
    @GetMapping("/api/session")
    public ResponseEntity<Map<String, Boolean>> session() {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("ready", true));
    }
}
