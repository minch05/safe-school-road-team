package com.safeschoolroad.trip;

import com.safeschoolroad.member.*;
import com.safeschoolroad.family.*;
import com.safeschoolroad.route.*;
import com.safeschoolroad.push.PushNotifications;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 별도 DB 트랜잭션의 동시 요청. 합성 회원/기록은 finally에서 삭제한다. */
@SpringBootTest
class TripStartConcurrencyTests {
    @Autowired TripService service;
    @Autowired MemberRepository members;
    @Autowired TripRepository trips;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean RouteService routes;
    @MockitoBean FamilyAccess access;
    @MockitoBean PushNotifications notifications;

    @Test void 겹친_출발_요청은_같은기록을_반환하고_알림은_한번만_요청한다() throws Exception {
        var tx=new TransactionTemplate(manager);
        Member child=tx.execute(status -> members.saveAndFlush(new Member("race"+UUID.randomUUID().toString().substring(0,8),"unused","합성검수",true)));
        var pool=Executors.newFixedThreadPool(2);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            var route=new Route(child,child,"합성검수","출발",37.59,127.08,"도착",37.6,127.09,0,Route.Tone.LOWER);
            ReflectionTestUtils.setField(route,"id",123456L);
            when(routes.get(any(),eq(123456L))).thenAnswer(inv -> {
                entered.countDown();
                if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("검사 동기화 실패");
                return route;
            });
            when(access.guardianOf(any())).thenReturn(Optional.of(new GuardianLink(child,child,GuardianLink.Type.CREATED)));
            String request=UUID.randomUUID().toString();
            var first=pool.submit(() -> service.start(child,123456L,600,request).getId());
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var secondEntered=new CountDownLatch(1);
            var second=pool.submit(() -> {secondEntered.countDown();return service.start(child,123456L,600,request).getId();});
            assertThat(secondEntered.await(10,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            // 첫 요청이 DB 회원 잠금을 가진 동안 두 번째 요청은 완료되지 않는다.
            release.countDown();
            assertThat(second.get(20,TimeUnit.SECONDS)).isEqualTo(first.get(20,TimeUnit.SECONDS));
            int count=tx.execute(status -> trips.findByChild(child).size());
            assertThat(count).isEqualTo(1);
            verify(notifications,times(1)).send(any(),anyString(),anyString(),anyString(),any(),any(),eq("START"));
        } finally {
            release.countDown();pool.shutdown();
            if(!pool.awaitTermination(30,TimeUnit.SECONDS)) {pool.shutdownNow();pool.awaitTermination(10,TimeUnit.SECONDS);}
            tx.executeWithoutResult(status -> {trips.deleteAll(trips.findByChild(child));trips.flush();members.deleteById(child.getId());});
        }
    }
}
