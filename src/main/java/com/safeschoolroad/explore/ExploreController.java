package com.safeschoolroad.explore;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Arrays;
import java.util.UUID;

// 비로그인 일회성 경로 살펴보기 (FR-17, HANDOFF 5-7). 경로 비교까지만. 안내문·길 안내는 로그인 후에(AI를 쓰지 않으려고).
// 경로 차이 설명(AI)도 비로그인에는 정해진 문장만 쓴다.
@Controller
public class ExploreController {

    static final String DEVICE_COOKIE = "ssr_device";
    public static final String PENDING = "pendingRoute";

    private final GuestQuota quota;

    public ExploreController(GuestQuota quota) {
        this.quota = quota;
    }

    @GetMapping("/explore")
    public String explore(HttpServletRequest request, HttpServletResponse response, Model model) {
        model.addAttribute("guest", true);
        model.addAttribute("guestLeft", quota.left(device(request, response)));
        return "route-new";
    }

    static String device(HttpServletRequest request, HttpServletResponse response) {
        String id = request.getCookies() == null ? null : Arrays.stream(request.getCookies())
                .filter(c -> DEVICE_COOKIE.equals(c.getName())).map(Cookie::getValue)
                .filter(v -> v.matches("[0-9a-f-]{36}")).findFirst().orElse(null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            Cookie c = new Cookie(DEVICE_COOKIE, id);
            c.setPath("/");
            c.setHttpOnly(true);
            c.setMaxAge(60 * 60 * 24 * 365);
            response.addCookie(c);
        }
        return id;
    }
}
