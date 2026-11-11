package com.safeschoolroad.explore;

import com.safeschoolroad.external.TmapClient;
import com.safeschoolroad.route.RouteCandidateService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ExploreQuotaContractTests {
    final GuestQuota quota=mock(GuestQuota.class);
    final TmapClient tmap=mock(TmapClient.class);
    final RouteCandidateService routes=mock(RouteCandidateService.class);
    final ExploreApiController api=new ExploreApiController(quota,routes,tmap);
    final ExploreApiController.CandidateRequest input=new ExploreApiController.CandidateRequest(
        new ExploreApiController.PlaceInput("출발",37.59,126.94),new ExploreApiController.PlaceInput("도착",37.60,126.95));
    @Test void 남은횟수조회는_외부호출과_차감없이_캐시금지() {
        when(quota.left(anyString())).thenReturn(2);
        var response=new MockHttpServletResponse();
        assertThat(api.remaining(new MockHttpServletRequest(),response)).containsEntry("left",2);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        verify(quota,never()).tryUse(anyString());verifyNoInteractions(tmap,routes);
    }
    @Test void 모든옵션캐시는_소진후에도_차감하지않는다() {
        when(tmap.isCachedToday(any(),any(),anyInt(),anyList())).thenReturn(true);
        when(routes.find(any(),any())).thenReturn(List.of());
        api.candidates(input,new MockHttpServletRequest(),new MockHttpServletResponse());
        verify(quota,never()).tryUse(anyString());verify(routes).find(any(),any());
    }
    @Test void 일부옵션만캐시라면_한도검사후_외부호출을막는다() {
        when(tmap.isCachedToday(any(),any(),anyInt(),anyList())).thenReturn(true,false);
        var response=new MockHttpServletResponse();
        api.candidates(input,new MockHttpServletRequest(),response);
        assertThat(response.getStatus()).isEqualTo(429);verify(quota).tryUse(anyString());verifyNoInteractions(routes);
    }
    @Test void 외부실패후에도_이미사용한횟수는_재조회할수있다() {
        when(quota.tryUse(anyString())).thenReturn(true);
        when(routes.find(any(),any())).thenThrow(new IllegalStateException("외부 실패"));
        assertThatThrownBy(()->api.candidates(input,new MockHttpServletRequest(),new MockHttpServletResponse())).isInstanceOf(IllegalStateException.class);
        verify(quota).tryUse(anyString());when(quota.left(anyString())).thenReturn(2);
        assertThat(api.remaining(new MockHttpServletRequest(),new MockHttpServletResponse())).containsEntry("left",2);
    }
}
