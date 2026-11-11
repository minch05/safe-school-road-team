package com.safeschoolroad.push;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class PushRecoveryDispatchTests {
    @Test void 개별처리실패는_나머지를_막지않고_다음복구도_실행된다(){
        var repo=mock(PushPendingRepository.class);var processor=mock(PushPendingProcessor.class);
        when(repo.dueIds(any(),any())).thenReturn(List.of(1L,2L,3L));
        doThrow(new IllegalStateException("합성 실패")).doNothing().when(processor).process(1L);
        var delivery=new PushNotificationDelivery(repo,processor);
        assertThatCode(delivery::recover).doesNotThrowAnyException();
        var order=inOrder(processor);order.verify(processor).process(1L);order.verify(processor).process(2L);order.verify(processor).process(3L);
        delivery.recover();verify(processor,times(2)).process(1L);verify(processor,times(2)).process(3L);
        verify(repo,times(2)).dueIds(any(),argThat((Pageable page)->page.getPageNumber()==0&&page.getPageSize()==20));
    }
    @Test void 목록조회실패후_다음호출에서는_다시_조회한다(){
        var repo=mock(PushPendingRepository.class);var processor=mock(PushPendingProcessor.class);
        when(repo.dueIds(any(),any())).thenThrow(new IllegalStateException("합성 DB 실패")).thenReturn(List.of(4L));
        var delivery=new PushNotificationDelivery(repo,processor);
        assertThatThrownBy(delivery::recover).isInstanceOf(IllegalStateException.class);verifyNoInteractions(processor);
        delivery.recover();verify(processor).process(4L);
    }
}
