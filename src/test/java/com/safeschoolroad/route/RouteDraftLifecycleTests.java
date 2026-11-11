package com.safeschoolroad.route;
import com.safeschoolroad.member.*;
import com.safeschoolroad.family.FamilyAccess;
import com.safeschoolroad.place.PlaceRepository;
import com.safeschoolroad.external.VworldSearchClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class RouteDraftLifecycleTests {
    final CurrentMember current=mock(CurrentMember.class);
    final RouteService service=mock(RouteService.class);
    final Member member=mock(Member.class);
    final RouteApiController controller=new RouteApiController(current,mock(FamilyAccess.class),mock(RouteCandidateService.class),service,mock(PlaceRepository.class),mock(VworldSearchClient.class));
    RouteDraft draft(){return new RouteDraft(1,new RouteDraft.Place("테스트 출발",37.6,127),new RouteDraft.Place("테스트 도착",37.61,127),0,Route.Tone.LOWER,null,List.of(),"TEMPLATE",0,List.of(),0);}
    void login(){when(current.get(null)).thenReturn(member);when(member.getId()).thenReturn(1L);}
    @Test void 같은_저장요청을_다시_보내도_하나만_저장한다(){
        login();var session=new MockHttpSession();session.setAttribute(RouteApiController.DRAFT,draft());session.setAttribute("draftRequestId","d1");
        var route=mock(Route.class);when(route.getId()).thenReturn(10L);when(service.save(eq(member),any(),eq("통학"),eq(false),eq(false))).thenReturn(route);
        var request=new RouteApiController.SaveRequest("통학",false,false,"d1","save1");
        assertThat(controller.save(request,null,session)).containsEntry("id",10L);
        assertThat(controller.save(request,null,session)).containsEntry("id",10L);
        verify(service,times(1)).save(eq(member),any(),eq("통학"),eq(false),eq(false));
    }
    @Test void 이전_안내문_화면에서는_최신_초안을_저장하지_않는다(){
        login();var session=new MockHttpSession();session.setAttribute(RouteApiController.DRAFT,draft());session.setAttribute("draftRequestId","new");
        assertThatThrownBy(()->controller.save(new RouteApiController.SaveRequest("통학",false,false,"old","save"),null,session)).hasMessageContaining("안내문이 바뀌었어요");verifyNoInteractions(service);
    }
    @Test void 늦게_도착한_안내문이_새_초안을_덮지_않는다(){
        login();var session=new MockHttpSession();
        when(service.makeDraft(any(),any(),any(),any(),anyInt(),any(),any())).thenAnswer(inv->{session.setAttribute("draftRequestId","new");return draft();});
        var p=new RouteApiController.PlaceInput("테스트",37.6,127.0);
        assertThatThrownBy(()->controller.draft(new RouteApiController.DraftRequest(p,p,0,null,Route.Tone.LOWER,null,"old"),null,session)).hasMessageContaining("최근");
        assertThat(session.getAttribute(RouteApiController.DRAFT)).isNull();
    }
    @Test void 만료된_초안은_저장하지_않고_재생성_가능한_오류를_준다(){
        login();var session=new MockHttpSession();
        var error=catchThrowable(()->controller.save(new RouteApiController.SaveRequest("통학",false,false,null,"save"),null,session));
        assertThat(error).isInstanceOf(DraftUnavailableException.class).hasMessageContaining("만료", "유지", "다시");
        var response=new com.safeschoolroad.config.ApiErrorHandler().draftUnavailable((DraftUnavailableException)error);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsEntry("code","DRAFT_UNAVAILABLE");
        verifyNoInteractions(service);
    }
}
