package com.safeschoolroad.trip;

import com.safeschoolroad.member.Member;
import com.safeschoolroad.member.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TripArrivalPersistenceTests {
    @Autowired TripRepository trips;
    @Autowired MemberRepository members;

    @Test void 최초_도착만_기록하고_다른아이와_재시도는_기록을_바꾸지_않는다() {
        Member child=members.saveAndFlush(new Member("arrival"+java.util.UUID.randomUUID().toString().substring(0,8),"unused","검수",true));
        long childId=child.getId();
        long id=trips.saveAndFlush(new Trip(child,123L,"합성 검수",600)).getId();
        var time=LocalDateTime.of(2026,10,10,8,0);
        assertThat(trips.recordArrival(id,childId+1000000,time,true)).isZero();
        assertThat(trips.recordArrival(id,childId,time,false)).isEqualTo(1);
        assertThat(trips.recordArrival(id,childId,time.plusMinutes(1),true)).isZero();
        Trip recorded=trips.findById(id).orElseThrow();
        assertThat(recorded.getArrivedAt()).isEqualTo(time);
        assertThat(recorded.isArrivedAuto()).isFalse();
    }
    @Test void 지연알림은_한번만_차지하고_도착후나_예상시간삭제후에는_차지하지_않는다() {
        Member child=members.saveAndFlush(new Member("delay"+java.util.UUID.randomUUID().toString().substring(0,8),"unused","검수",true));
        long childId=child.getId();
        long first=trips.saveAndFlush(new Trip(child,123L,"검수",600)).getId();
        long second=trips.saveAndFlush(new Trip(child,123L,"검수",600)).getId();
        long third=trips.saveAndFlush(new Trip(child,123L,"검수",0)).getId();
        var now=LocalDateTime.now();var since=now.minusHours(24);
        assertThat(trips.recordDelayAlert(first,now,since,600)).isEqualTo(1);
        assertThat(trips.recordDelayAlert(first,now.plusSeconds(1),since,600)).isZero();
        assertThat(trips.recordArrival(second,childId,now,false)).isEqualTo(1);
        assertThat(trips.recordDelayAlert(second,now,since,600)).isZero();
        assertThat(trips.recordDelayAlert(third,now,since,600)).isZero();
        assertThat(trips.recordDelayAlert(third,now,since,0)).isZero();
    }
}
