package com.safeschoolroad.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

// 모든 화면이 같이 쓰는 값. VWorld 지도 키는 브라우저가 타일을 직접 받으므로 화면에 넘긴다
// (VWorld 사이트에 사용할 도메인을 등록해 두어야 한다, .env.example 참고).
@ControllerAdvice(annotations = Controller.class)
public class PageDefaults {

    @Value("${VWORLD_API_KEY:}")
    private String vworldApiKey;

    @Value("${NAVER_MAPS_CLIENT_ID:}")
    private String naverMapsClientId;

    @ModelAttribute("naverMapsClientId")
    public String naverMapsClientId() { return naverMapsClientId; }

    @Value("${VAPID_PUBLIC_KEY:}")
    private String vapidPublicKey;

    @ModelAttribute("vworldApiKey")
    public String vworldApiKey() {
        return vworldApiKey;
    }

    @ModelAttribute("vapidPublicKey")
    public String vapidPublicKey() {
        return vapidPublicKey;
    }
}
