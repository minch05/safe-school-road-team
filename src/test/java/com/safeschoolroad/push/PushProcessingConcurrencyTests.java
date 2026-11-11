package com.safeschoolroad.push;

import com.safeschoolroad.member.*;
import com.safeschoolroad.family.GuardianLinkRepository;
import com.safeschoolroad.trip.TripRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class PushProcessingConcurrencyTests {
    @Autowired PushPendingRepository pending;
    @Autowired PushSubscriptionRepository subscriptions;
    @Autowired MemberRepository members;
    @Autowired GuardianLinkRepository links;
    @Autowired TripRepository trips;
    @Autowired PlatformTransactionManager manager;
    @Test void 두처리기가_같은대기를_잡아도_커밋된성공은_한번만_전송한다() throws Exception {
        var tx=new TransactionTemplate(manager);
        var now=LocalDateTime.of(2077,10,10,12,0);
        var clock=Clock.fixed(now.atZone(ZoneId.systemDefault()).toInstant(),ZoneId.systemDefault());
        var member=tx.execute(s->members.saveAndFlush(new Member("pushrace"+UUID.randomUUID().toString().substring(0,8),"unused","합성검수",true)));
        Long subId=null,taskId=null;var pool=Executors.newFixedThreadPool(2);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            var sub=tx.execute(s->subscriptions.saveAndFlush(new PushSubscription(member,"https://fcm.googleapis.com/synthetic-"+UUID.randomUUID(),"key","auth")));
            subId=sub.getId();taskId=tx.execute(s->pending.saveAndFlush(new PushPending(sub,"검수","합성","/",now)).getId());
            var transport=mock(PushService.class);
            when(transport.sendOne(any(),anyString(),anyString(),anyString(),anyString())).thenAnswer(inv->{
                entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("검사 동기화 실패");return PushService.DeliveryResult.ACCEPTED;
            });
            var firstProcessor=new PushPendingProcessor(pending,subscriptions,transport,links,trips);
            var secondProcessor=new PushPendingProcessor(pending,subscriptions,transport,links,trips);
            ReflectionTestUtils.setField(firstProcessor,"clock",clock);ReflectionTestUtils.setField(secondProcessor,"clock",clock);
            final long id=taskId;
            var first=pool.submit(()->tx.executeWithoutResult(s->firstProcessor.process(id)));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var secondStarted=new CountDownLatch(1);
            var second=pool.submit(()->{secondStarted.countDown();tx.executeWithoutResult(s->secondProcessor.process(id));});
            assertThat(secondStarted.await(10,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->second.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();first.get(20,TimeUnit.SECONDS);second.get(20,TimeUnit.SECONDS);
            verify(transport,times(1)).sendOne(any(),eq("검수"),eq("합성"),eq("/"),eq("pending-"+id));
            assertThat(pending.findById(id)).isEmpty();
        } finally {
            release.countDown();pool.shutdown();if(!pool.awaitTermination(30,TimeUnit.SECONDS)){pool.shutdownNow();pool.awaitTermination(10,TimeUnit.SECONDS);}
            final Long task=taskId,sub=subId;
            tx.executeWithoutResult(s->{if(task!=null)pending.deleteById(task);if(sub!=null)subscriptions.deleteById(sub);members.deleteById(member.getId());});
        }
    }
}
