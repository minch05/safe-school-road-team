package com.safeschoolroad.push;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.LocalDateTime;
@Service
public class PushNotificationDelivery {
    private final PushPendingRepository pending;
    private final PushPendingProcessor processor;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(PushNotificationDelivery.class);
    public PushNotificationDelivery(PushPendingRepository pending,PushPendingProcessor processor){this.pending=pending;this.processor=processor;}
    public void send(PushNotifications.Message message){message.taskIds().forEach(this::process);}
    private void process(long id){try{processor.process(id);}catch(RuntimeException e){log.warn("대기 알림 처리 실패: {}",e.getClass().getSimpleName());}}
    @Scheduled(fixedDelay=15000,initialDelay=30000)
    public void recover(){pending.dueIds(LocalDateTime.now(),org.springframework.data.domain.PageRequest.of(0,20)).forEach(this::process);}
}
