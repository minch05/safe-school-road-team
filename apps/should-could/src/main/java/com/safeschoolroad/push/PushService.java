package com.safeschoolroad.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.member.Member;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClients;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Security;
import java.util.List;
import java.util.Map;

// 웹 푸시 보내기 (FR-20). 보호자에게 출발·도착·도착 지연 알림을 보낸다.
// 아이폰은 iOS 16.4 이상에서 홈 화면에 설치한 앱만 받는다(사전 시험 확인). 안드로이드 크롬은 브라우저에서도 받는다.
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final PushSubscriptionRepository subscriptions;
    private final ObjectMapper objectMapper;

    @Value("${VAPID_PUBLIC_KEY:}")
    private String publicKey;

    @Value("${VAPID_PRIVATE_KEY:}")
    private String privateKey;

    // 푸시 서버(애플·구글)가 문제 있을 때 연락할 곳. 개인 이메일 대신 서비스용 주소를 쓴다
    @Value("${VAPID_SUBJECT:mailto:safe-school-road@example.com}")
    private String subject;

    public PushService(PushSubscriptionRepository subscriptions, ObjectMapper objectMapper) {
        this.subscriptions = subscriptions;
        this.objectMapper = objectMapper;
    }

    public boolean registered(Member member, String endpoint) {
        return subscriptions.existsByMemberAndEndpoint(member, endpoint);
    }

    public boolean isConfigured() {
        return !publicKey.isBlank() && !privateKey.isBlank();
    }

    @Transactional
    public void subscribe(Member member, String endpoint, String p256dh, String auth) {
        PushEndpoint.requireAllowed(endpoint);
        // 같은 기기가 다른 계정으로 로그인했으면 그 계정으로 옮긴다
        subscriptions.findByEndpoint(endpoint).ifPresentOrElse(
                s -> s.update(member, p256dh, auth),
                () -> subscriptions.save(new PushSubscription(member, endpoint, p256dh, auth)));
    }

    @Transactional
    public void unsubscribe(Member member, String endpoint) {
        subscriptions.findByEndpoint(endpoint)
                .filter(s -> s.getMember().getId().equals(member.getId()))
                .ifPresent(subscriptions::delete);
    }

    /** @return 보낸 기기 수 */
    @Transactional
    public int send(Member to, String title, String body, String url) {
        if (!isConfigured()) {
            log.warn("VAPID 키가 없어 푸시를 보내지 않음");
            return 0;
        }
        List<PushSubscription> targets = subscriptions.findByMember(to);
        return (int) targets.stream().filter(target -> sendOne(target, title, body, url) == DeliveryResult.ACCEPTED).count();
    }

    public enum DeliveryResult { ACCEPTED, RETRY, EXPIRED, REJECTED, NOT_CONFIGURED }

    /** 기기 하나의 결과. ACCEPTED는 제공기관 접수이며 사용자 수신 확인이 아니다. */
    @Transactional
    public DeliveryResult sendOne(PushSubscription s, String title, String body, String url) {
        return sendOne(s,title,body,url,null);
    }

    @Transactional
    public DeliveryResult sendOne(PushSubscription s, String title, String body, String url, String notificationTag) {
        if (!isConfigured()) return DeliveryResult.NOT_CONFIGURED;
        if (!PushEndpoint.allowed(s.getEndpoint())) return DeliveryResult.REJECTED;
        try {
            var message=new java.util.LinkedHashMap<String,String>();
            message.put("title",title);message.put("body",body);message.put("url",url);
            if(notificationTag!=null)message.put("tag",notificationTag);
            String payload = objectMapper.writeValueAsString(message);
            nl.martijndwars.webpush.PushService sender =
                    new nl.martijndwars.webpush.PushService(publicKey, privateKey, subject);
            var config = RequestConfig.custom().setConnectTimeout(5000)
                    .setConnectionRequestTimeout(5000).setSocketTimeout(8000).build();
            try (var client = HttpClients.custom().disableRedirectHandling().setDefaultRequestConfig(config).build();
                 var res = client.execute(sender.preparePost(new Notification(s.getEndpoint(), s.getP256dh(), s.getAuth(), payload), Encoding.AES128GCM))) {
                DeliveryResult result = classify(res.getStatusLine().getStatusCode());
                if (result == DeliveryResult.EXPIRED) subscriptions.delete(s);
                return result;
            }
        } catch (Exception e) {
            log.warn("푸시 보내기 오류 유형: {}", e.getClass().getSimpleName());
            return DeliveryResult.RETRY;
        }
    }

    static DeliveryResult classify(int status) {
        if (status >= 200 && status < 300) return DeliveryResult.ACCEPTED;
        if (status == 404 || status == 410) return DeliveryResult.EXPIRED;
        if (status == 408 || status == 429 || status >= 500 && status < 600) return DeliveryResult.RETRY;
        return DeliveryResult.REJECTED;
    }
}
