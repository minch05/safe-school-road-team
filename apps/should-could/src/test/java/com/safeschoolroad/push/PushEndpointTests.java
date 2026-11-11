package com.safeschoolroad.push;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class PushEndpointTests {
    @Test void 지원_제공기관의_HTTPS_주소만_허용한다(){
        assertThat(PushEndpoint.allowed("https://fcm.googleapis.com/wp/test-token")).isTrue();
        assertThat(PushEndpoint.allowed("https://web.push.apple.com/test-token")).isTrue();
        assertThat(PushEndpoint.allowed("https://a.push.apple.com:443/test-token")).isTrue();
        assertThat(PushEndpoint.allowed("https://updates.push.services.mozilla.com/wpush/v2/test-token")).isTrue();
    }
    @Test void 내부_주소와_위장_도메인과_인증정보_포트를_차단한다(){
        for(String bad:new String[]{"https://127.0.0.1/admin","https://localhost/admin","https://169.254.169.254/latest/meta-data/",
                "https://fcm.googleapis.com.attacker.test/wp/x","https://web.push.apple.com.attacker.test/x",
                "https://fcm.googleapis.com@attacker.test/x","https://user@fcm.googleapis.com/wp/x",
                "https://fcm.googleapis.com:8443/wp/x","http://fcm.googleapis.com/wp/x","https://fcm.googleapis.com/wp/x#fragment",
                "https://fcm.googleapis.com","not a URL"})assertThat(PushEndpoint.allowed(bad)).as(bad).isFalse();
        assertThat(PushEndpoint.allowed(null)).isFalse();
        assertThat(PushEndpoint.allowed("https://fcm.googleapis.com/wp/"+"a".repeat(2048))).isFalse();
    }
}
