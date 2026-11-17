package com.safeschoolroad.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Security;
import java.util.Map;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClients;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class PushTransport {
    enum Result { ACCEPTED, RETRY, EXPIRED, REJECTED }

    private static final Logger LOG = LoggerFactory.getLogger(PushTransport.class);
    private final ObjectMapper mapper;
    private final String publicKey;
    private final String privateKey;
    private final String subject;

    PushTransport(ObjectMapper mapper, @Value("${VAPID_PUBLIC_KEY:}") String publicKey,
                  @Value("${VAPID_PRIVATE_KEY:}") String privateKey, @Value("${VAPID_SUBJECT:}") String subject) {
        this.mapper = mapper;
        this.publicKey = publicKey.strip();
        this.privateKey = privateKey.strip();
        this.subject = subject.strip();
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    String publicKey() {
        return privateKey.isBlank() || subject.isBlank() ? "" : publicKey;
    }

    Result send(WebPush.Subscription target, String body, String tag) {
        if (!PushEndpoint.allowed(target.endpoint())) return Result.REJECTED;
        try {
            String payload = mapper.writeValueAsString(Map.of("title", "안전한 통학길",
                    "body", body, "url", "/family.html", "tag", tag));
            var sender = new nl.martijndwars.webpush.PushService(publicKey, privateKey, subject);
            var notification = new Notification(target.endpoint(), target.key(), target.secret(), payload);
            var config = RequestConfig.custom().setConnectTimeout(5000)
                    .setConnectionRequestTimeout(5000).setSocketTimeout(8000).build();
            try (var client = HttpClients.custom().disableRedirectHandling().setDefaultRequestConfig(config).build();
                 var response = client.execute(sender.preparePost(notification, Encoding.AES128GCM))) {
                int status = response.getStatusLine().getStatusCode();
                if (status >= 200 && status < 300) return Result.ACCEPTED;
                if (status == 404 || status == 410) return Result.EXPIRED;
                if (status == 408 || status == 429 || status >= 500) return Result.RETRY;
                return Result.REJECTED;
            }
        } catch (Exception e) {
            LOG.warn("웹 푸시 처리 실패 유형: {}", e.getClass().getSimpleName());
            return Result.RETRY;
        }
    }
}
