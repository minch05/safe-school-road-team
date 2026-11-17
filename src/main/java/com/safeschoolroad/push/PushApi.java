package com.safeschoolroad.push;

import com.safeschoolroad.family.FamilyService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class PushApi {
    private final FamilyService family;
    private final WebPush push;

    PushApi(FamilyService family, WebPush push) {
        this.family = family;
        this.push = push;
    }

    record Keys(String p256dh, String auth) {}
    record Subscription(String endpoint, Keys keys) {}
    record Endpoint(String endpoint) {}

    @GetMapping("/api/push/config")
    Map<String, String> config() {
        return Map.of("publicKey", push.publicKey());
    }

    @PostMapping("/api/push/subscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void subscribe(@RequestBody Subscription body, Authentication auth) {
        if (body.keys() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "구독 키가 없어요.");
        push.subscribe(member(auth), body.endpoint(), body.keys().p256dh(), body.keys().auth());
    }

    @PostMapping("/api/push/status")
    Map<String, Boolean> status(@RequestBody Endpoint body, Authentication auth) {
        return Map.of("registered", push.registered(member(auth), body.endpoint()));
    }

    @PostMapping("/api/push/unsubscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unsubscribe(@RequestBody Endpoint body, Authentication auth) {
        push.unsubscribe(member(auth), body.endpoint());
    }

    @PostMapping("/api/push/test")
    Map<String, Integer> test(@RequestBody Endpoint body, Authentication auth) {
        return Map.of("sent", push.test(member(auth), body.endpoint()));
    }

    private long member(Authentication auth) {
        return family.memberId(auth.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}
