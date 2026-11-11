package com.safeschoolroad.push;
import com.safeschoolroad.member.Member;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest @Transactional
class PushPendingPersistenceTests {
    @Autowired PushPendingRepository pending;
    @Autowired PushSubscriptionRepository subscriptions;
    @Autowired EntityManager em;
    @Autowired com.safeschoolroad.member.MemberRepository members;
    @Autowired com.safeschoolroad.family.GuardianLinkRepository links;
    @Autowired com.safeschoolroad.family.FamilyService family;
    @Test void 대기기록은_영속성컨텍스트를_비운뒤에도_복구된다(){
        var member=new Member("unused","unused","검수",true);ReflectionTestUtils.setField(member,"id",999999L);
        var sub=new PushSubscription(member,"https://fcm.googleapis.com/example","key","auth");ReflectionTestUtils.setField(sub,"id",999999L);
        var now=LocalDateTime.now();
        long id=pending.saveAndFlush(new PushPending(sub,"검수","합성","/",now)).getId();em.clear();
        var restored=pending.lockPending(id).orElseThrow();
        assertThat(restored.getBody()).isEqualTo("합성");assertThat(restored.matches(sub)).isTrue();
        assertThat(pending.dueIds(now.plusSeconds(1),org.springframework.data.domain.PageRequest.of(0,20))).contains(id);
    }
    void cancel(boolean deleteChild) {
        String suffix=java.util.UUID.randomUUID().toString().substring(0,8);
        var parent=members.saveAndFlush(new Member("parent"+suffix,"unused","검수",true));
        var child=members.saveAndFlush(new Member("child"+suffix,"unused","검수",true));
        var link=links.saveAndFlush(new com.safeschoolroad.family.GuardianLink(parent,child,deleteChild?com.safeschoolroad.family.GuardianLink.Type.CREATED:com.safeschoolroad.family.GuardianLink.Type.INVITED));
        var sub=new PushSubscription(parent,"https://fcm.googleapis.com/example","key","auth");ReflectionTestUtils.setField(sub,"id",999999L);
        long id=pending.saveAndFlush(new PushPending(sub,"검수","합성","/",LocalDateTime.now(),link.getId())).getId();
        if(deleteChild)family.deleteChild(parent,child.getId());else family.unlink(parent,child.getId());
        em.flush();em.clear();
        assertThat(pending.findById(id)).isEmpty();
    }
    @Test void 연동해제하면_해당_알림대기를_삭제한다(){cancel(false);}
    @Test void 아이삭제하면_보호자에게_보낼_알림대기도_삭제한다(){cancel(true);}
    @Test void 구독세대는_DB에_보관되어_계정복귀후에도_옛알림을_차단한다(){
        String suffix=java.util.UUID.randomUUID().toString().substring(0,8);
        var first=members.saveAndFlush(new Member("first"+suffix,"unused","검수",true));
        var second=members.saveAndFlush(new Member("second"+suffix,"unused","검수",true));
        var sub=subscriptions.saveAndFlush(new PushSubscription(first,"https://fcm.googleapis.com/test-"+suffix,"key","auth"));
        var task=new PushPending(sub,"검수","합성","/",LocalDateTime.now());
        long id=sub.getId();sub.update(second,"key","auth");sub.update(first,"key","auth");em.flush();em.clear();
        var restored=subscriptions.lockForDelivery(id).orElseThrow();
        assertThat(restored.getRegistrationGeneration()).isNotBlank();assertThat(task.matches(restored)).isFalse();
    }
    @Test void 재시도예약전에는_복구대상에서_제외되고_예약시각부터_포함된다(){
        var member=new Member("unused","unused","검수",true);ReflectionTestUtils.setField(member,"id",999999L);
        var sub=new PushSubscription(member,"https://fcm.googleapis.com/example","key","auth");ReflectionTestUtils.setField(sub,"id",999999L);
        var now=LocalDateTime.of(2077,10,10,12,0);
        var task=new PushPending(sub,"검수","합성","/",now);task.retry(now);
        long id=pending.saveAndFlush(task).getId();em.clear();
        var page=org.springframework.data.domain.PageRequest.of(0,20);
        assertThat(pending.dueIds(now.plusSeconds(29),page)).doesNotContain(id);
        assertThat(pending.dueIds(now.plusSeconds(30),page)).contains(id);
    }
    @Test void 재시도예약이_늦어도_만료된대기는_정리대상에_포함된다(){
        var member=new Member("unused","unused","검수",true);ReflectionTestUtils.setField(member,"id",999999L);
        var sub=new PushSubscription(member,"https://fcm.googleapis.com/example","key","auth");ReflectionTestUtils.setField(sub,"id",999999L);
        var now=LocalDateTime.of(2077,10,10,12,0);
        var task=new PushPending(sub,"검수","합성","/",now);
        task.retry(now.plusMinutes(10));long id=pending.saveAndFlush(task).getId();em.clear();
        var restored=pending.findById(id).orElseThrow();assertThat(restored.due(now.plusMinutes(5))).isFalse();
        assertThat(restored.expired(now.plusMinutes(5))).isTrue();
        assertThat(pending.dueIds(now.plusMinutes(5),org.springframework.data.domain.PageRequest.of(0,20))).contains(id);
    }
}
