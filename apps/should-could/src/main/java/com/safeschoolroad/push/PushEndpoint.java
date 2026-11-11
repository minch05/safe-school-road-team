package com.safeschoolroad.push;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** 임의 HTTPS 주소로 서버 요청을 보내지 않는다. 지원 브라우저의 푸시 제공기관만 허용한다. */
public final class PushEndpoint {
    private static final Set<String> HOSTS = Set.of("fcm.googleapis.com", "updates.push.services.mozilla.com");
    private PushEndpoint() {}
    public static boolean allowed(String value) {
        if (value == null || value.length() > 2048) return false;
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getPath() == null || uri.getPath().length() < 2) return false;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return HOSTS.contains(host) || host.endsWith(".push.apple.com");
        } catch (IllegalArgumentException e) { return false; }
    }
    public static void requireAllowed(String endpoint) {
        if (!allowed(endpoint)) throw new IllegalArgumentException("이 브라우저의 알림 주소는 지원하지 않아요. 사파리나 크롬에서 다시 시도해 주세요.");
    }
}
