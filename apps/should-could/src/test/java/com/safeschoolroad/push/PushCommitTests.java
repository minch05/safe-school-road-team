package com.safeschoolroad.push;

import com.safeschoolroad.member.Member;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class PushCommitTests {
    @Autowired PushNotifications notifications;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean PushNotificationDelivery delivery;
    Member recipient() {var m=new Member("unused","unused","검수",true);ReflectionTestUtils.setField(m,"id",999999L);return m;}
    @Test void 저장이_완료된뒤에만_알림을_전송한다() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            notifications.send(recipient(),"검수","합성 알림","/");
            verifyNoInteractions(delivery);
        });
        verify(delivery).send(any(PushNotifications.Message.class));
    }
    @Test void 저장을_취소하면_알림도_전송하지_않는다() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            notifications.send(recipient(),"검수","합성 알림","/");status.setRollbackOnly();
        });
        verifyNoInteractions(delivery);
    }
    @Test void 커밋뒤_알림실패가_저장실패로_응답되지_않는다() {
        doThrow(new IllegalStateException("합성 실패")).when(delivery).send(any());
        assertThatCode(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
            notifications.send(recipient(),"검수","합성 알림","/"))).doesNotThrowAnyException();
        verify(delivery).send(any());
    }
}
