package com.safeschoolroad.push;
import com.safeschoolroad.member.Member;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class PushPendingTests {
    Member member(long id){var m=new Member("test","unused","검수",true);ReflectionTestUtils.setField(m,"id",id);return m;}
    PushSubscription sub(){var s=new PushSubscription(member(1),"https://fcm.googleapis.com/example","testkey","testauth");ReflectionTestUtils.setField(s,"id",9L);return s;}
    @Test void 실패기기만_재시도하고_성공하면_대기열에서_삭제한다(){
        var repo=mock(PushPendingRepository.class);var subs=mock(PushSubscriptionRepository.class);var push=mock(PushService.class);
        var processor=new PushPendingProcessor(repo,subs,push,mock(com.safeschoolroad.family.GuardianLinkRepository.class),mock(com.safeschoolroad.trip.TripRepository.class));var s=sub();var now=LocalDateTime.now();
        var task=new PushPending(s,"검수","합성","/",now);
        when(repo.lockPending(4)).thenReturn(Optional.of(task));when(subs.lockForDelivery(9L)).thenReturn(Optional.of(s));
        when(push.sendOne(s,"검수","합성","/","pending-null")).thenReturn(PushService.DeliveryResult.RETRY);
        processor.process(4);verify(repo,never()).delete(any());assertThat(task.due(now.plusSeconds(29))).isFalse();
        processor.process(4);verify(push,times(1)).sendOne(any(),any(),any(),any(),any());
        var accepted=new PushPending(s,"검수","합성","/",now);when(repo.lockPending(5)).thenReturn(Optional.of(accepted));
        when(push.sendOne(s,"검수","합성","/","pending-null")).thenReturn(PushService.DeliveryResult.ACCEPTED);
        processor.process(5);verify(repo).delete(accepted);
    }
    @Test void 계정변경과_만료는_전송하지_않는다(){
        var repo=mock(PushPendingRepository.class);var subs=mock(PushSubscriptionRepository.class);var push=mock(PushService.class);
        var processor=new PushPendingProcessor(repo,subs,push,mock(com.safeschoolroad.family.GuardianLinkRepository.class),mock(com.safeschoolroad.trip.TripRepository.class));var s=sub();var now=LocalDateTime.now();
        var task=new PushPending(s,"검수","합성","/",now);s.update(member(2),"testkey","testauth");
        when(repo.lockPending(4)).thenReturn(Optional.of(task));when(subs.lockForDelivery(9L)).thenReturn(Optional.of(s));
        processor.process(4);verify(repo).delete(task);
        var expired=new PushPending(s,"검수","합성","/",now.minusMinutes(6));when(repo.lockPending(5)).thenReturn(Optional.of(expired));
        processor.process(5);verify(repo).delete(expired);verifyNoInteractions(push);
    }
    @Test void 도착후에는_출발지연알림을_재전송하지_않는다(){
        var s=sub();var now=LocalDateTime.now();
        var trip=new com.safeschoolroad.trip.Trip(member(1),12L,"검수",600);
        var start=new PushPending(s,"검수","합성","/",now,1L,2L,"START");
        var delay=new PushPending(s,"검수","합성","/",now,1L,2L,"DELAY");
        var arrival=new PushPending(s,"검수","합성","/",now,1L,2L,"ARRIVAL");
        assertThat(start.currentFor(trip)).isTrue();assertThat(delay.currentFor(trip)).isFalse();
        trip.markDelayAlertSent();assertThat(delay.currentFor(trip)).isTrue();
        trip.arrive(false);
        assertThat(start.currentFor(trip)).isFalse();assertThat(delay.currentFor(trip)).isFalse();
        assertThat(arrival.currentFor(trip)).isTrue();assertThat(arrival.currentFor(null)).isFalse();
    }
    @Test void 처리기는_도착한_통학의_대기출발알림을_삭제하고_전송하지_않는다(){
        var repo=mock(PushPendingRepository.class);var subs=mock(PushSubscriptionRepository.class);var push=mock(PushService.class);
        var links=mock(com.safeschoolroad.family.GuardianLinkRepository.class);var trips=mock(com.safeschoolroad.trip.TripRepository.class);
        var processor=new PushPendingProcessor(repo,subs,push,links,trips);
        var task=new PushPending(sub(),"검수","합성","/",LocalDateTime.now(),1L,2L,"START");
        var trip=new com.safeschoolroad.trip.Trip(member(1),12L,"검수",600);trip.arrive(false);
        when(repo.lockPending(4)).thenReturn(Optional.of(task));when(links.existsById(1L)).thenReturn(true);when(trips.findById(2L)).thenReturn(Optional.of(trip));
        processor.process(4);verify(repo).delete(task);verifyNoInteractions(subs,push);
    }
    @Test void 다른계정으로_전환후_돌아와도_옛알림은_되살아나지_않는다(){
        var s=sub();var task=new PushPending(s,"검수","합성","/",LocalDateTime.now());
        s.update(member(1),"testkey","testauth");assertThat(task.matches(s)).isTrue();
        s.update(member(2),"testkey","testauth");assertThat(task.matches(s)).isFalse();
        s.update(member(1),"testkey","testauth");assertThat(task.matches(s)).isFalse();
    }
}
