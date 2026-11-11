package com.safeschoolroad.push;

import com.safeschoolroad.member.CurrentMember;
import com.safeschoolroad.member.Member;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// 웹 푸시 구독 (보호자 폰에서 "알림 허용하기")
@RestController
public class PushApiController {

    private final CurrentMember current;
    private final PushService push;

    public PushApiController(CurrentMember current, PushService push) {
        this.current = current;
        this.push = push;
    }

    // 브라우저 PushSubscription.toJSON() 모양 그대로
    public record Keys(@NotBlank @Size(max = 512) String p256dh, @NotBlank @Size(max = 128) String auth) {
    }

    public record Subscription(@NotBlank @Size(max = 2048) String endpoint, @NotNull @Valid Keys keys) {
    }

    @PostMapping("/api/push/subscribe")
    public Map<String, Object> subscribe(@Valid @RequestBody Subscription s, Authentication auth) {
        PushEndpoint.requireAllowed(s.endpoint());
        push.subscribe(current.get(auth), s.endpoint(), s.keys().p256dh(), s.keys().auth());
        return Map.of("ok", true);
    }

    public record Endpoint(@NotBlank @Size(max = 2048) String endpoint) {
    }

    @PostMapping("/api/push/unsubscribe")
    public Map<String, Object> unsubscribe(@Valid @RequestBody Endpoint e, Authentication auth) {
        push.unsubscribe(current.get(auth), e.endpoint());
        return Map.of("ok", true);
    }

    // 현재 계정의 현재 브라우저 구독만 확인. 주소는 URL/응답에 노출하지 않는다.
    @PostMapping("/api/push/status")
    public org.springframework.http.ResponseEntity<Map<String, Boolean>> status(@Valid @RequestBody Endpoint e, Authentication auth) {
        return org.springframework.http.ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(Map.of("registered", push.registered(current.get(auth), e.endpoint())));
    }

    // 설정 화면의 "시험 알림 보내기"
    @PostMapping("/api/push/test")
    public Map<String, Object> test(Authentication auth) {
        Member me = current.get(auth);
        if (!push.isConfigured()) {
            throw new IllegalStateException("서버에 알림 키(VAPID)가 설정되지 않았어요.");
        }
        int sent = push.send(me, "안전한 통학길", "시험 알림이에요. 출발·도착 알림이 이렇게 와요.", "/");
        return Map.of("sent", sent);
    }
}
