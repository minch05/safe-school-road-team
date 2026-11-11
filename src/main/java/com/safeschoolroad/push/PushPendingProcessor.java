package com.safeschoolroad.push;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;
@Service
public class PushPendingProcessor {
    private java.time.Clock clock=java.time.Clock.systemDefaultZone();
    private final PushPendingRepository pending;
    private final PushSubscriptionRepository subscriptions;
    private final PushService push;
    private final com.safeschoolroad.trip.TripRepository trips;
    private final com.safeschoolroad.family.GuardianLinkRepository links;
    public PushPendingProcessor(PushPendingRepository pending,PushSubscriptionRepository subscriptions,PushService push,com.safeschoolroad.family.GuardianLinkRepository links,com.safeschoolroad.trip.TripRepository trips){this.pending=pending;this.subscriptions=subscriptions;this.push=push;this.links=links;this.trips=trips;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void process(long id){
        var item=pending.lockPending(id).orElse(null);if(item==null)return;
        var now=LocalDateTime.now(clock);
        if(item.expired(now)){pending.delete(item);return;}
        if(item.getGuardianLinkId()!=null&&!links.existsById(item.getGuardianLinkId())){pending.delete(item);return;}
        if(item.getTripId()!=null&&!item.currentFor(trips.findById(item.getTripId()).orElse(null))){pending.delete(item);return;}
        if(!item.due(now))return;
        var sub=subscriptions.lockForDelivery(item.getSubscriptionId()).orElse(null);
        if(sub==null||!item.matches(sub)){pending.delete(item);return;}
        var result=push.sendOne(sub,item.getTitle(),item.getBody(),item.getUrl(),"pending-"+item.getId());
        if(result==PushService.DeliveryResult.RETRY||result==PushService.DeliveryResult.NOT_CONFIGURED){item.retry(now);}
        else pending.delete(item);
    }
}
