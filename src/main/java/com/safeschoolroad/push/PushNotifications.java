package com.safeschoolroad.push;

import com.safeschoolroad.member.Member;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.event.TransactionalEventListener;

/** 통학 기록이 커밋된 뒤 전송한다. 롤백한 기록의 알림은 폐기한다. */
@Component
public class PushNotifications {
    public record Message(long recipientId, String title, String body, String url, java.util.List<Long> taskIds) {}
    private final ApplicationEventPublisher events;
    private final PushPendingRepository pending;
    private final PushSubscriptionRepository subscriptions;
    private final PushNotificationDelivery delivery;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(PushNotifications.class);
    public PushNotifications(ApplicationEventPublisher events, PushNotificationDelivery delivery, PushPendingRepository pending, PushSubscriptionRepository subscriptions) {
        this.events=events;this.delivery=delivery;this.pending=pending;this.subscriptions=subscriptions;
    }
    public void send(Member to,String title,String body,String url) {send(to,title,body,url,null);}
    public void send(Member to,String title,String body,String url,Long guardianLinkId) {send(to,title,body,url,guardianLinkId,null,null);}
    public void send(Member to,String title,String body,String url,Long guardianLinkId,Long tripId,String event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("통학 알림에는 기록 저장 트랜잭션이 필요합니다.");
        var ids=subscriptions.findByMember(to).stream().map(s -> pending.save(new PushPending(s,title,body,url,java.time.LocalDateTime.now(),guardianLinkId,tripId,event)).getId()).toList();
        events.publishEvent(new Message(to.getId(),title,body,url,ids));
    }
    @TransactionalEventListener
    public void deliver(Message message) {
        try {delivery.send(message);}
        catch (RuntimeException e) {
            // 커밋 후 알림 실패를 기록 저장 실패로 응답하지 않는다. 본문/회원 정보는 로그에 남기지 않는다.
            log.warn("기록 저장 후 알림 전송 실패: {}",e.getClass().getSimpleName());
        }
    }
}
