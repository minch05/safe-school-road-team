package com.safeschoolroad.route;
import com.safeschoolroad.member.*;
import com.safeschoolroad.family.*;
import com.safeschoolroad.facility.*;
import com.safeschoolroad.route.analysis.PointReason;
import com.safeschoolroad.trip.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest @AutoConfigureMockMvc @Transactional
class RouteManagementTests {
    @Autowired MockMvc mvc;@Autowired MemberRepository members;@Autowired RouteRepository routes;
    @Autowired TripRepository trips;@Autowired GuardianLinkRepository links;@Autowired EntityManager em;
    Member member(String name){return members.saveAndFlush(new Member(name,"unused","검수",true));}
    Route route(Member owner,Member creator){
        var r=new Route(owner,creator,"검수길","출발",37.59,127.08,"도착",37.6,127.09,0,Route.Tone.LOWER);
        var p=new RoutePoint(r,1,"TEST","testpoint",FacilityKind.CROSSWALK,"검수",37.59,127.08,SignalStatus.UNKNOWN,PointReason.CROSSING,0);
        p.setGuideText("횡단보도 앞에서 멈춰서 신호와 양쪽을 확인해요.");r.addPoint(p);return routes.saveAndFlush(r);
    }
    RoutePoint storedPoint(long id){em.flush();em.clear();return routes.findById(id).orElseThrow().getPoints().get(0);}
    @Test void 안내수정은_문장만_바꾸고_시설과_순서를_유지한다()throws Exception{
        var m=member("rmanageowner");long id=route(m,m).getId();
        mvc.perform(post("/api/routes/"+id+"/points/1/guide").with(user(m.getUsername())).with(csrf()).contentType("application/json").content("{\"text\":\"  멈춰서 신호와 양쪽을 확인해요.  \"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("ok").value(true));
        var p=storedPoint(id);assertThat(p.getGuideText()).isEqualTo("멈춰서 신호와 양쪽을 확인해요.");assertThat(p.isGuideEdited()).isTrue();
        assertThat(p.getFacilitySourceId()).isEqualTo("testpoint");assertThat(p.getSeq()).isEqualTo(1);assertThat(p.getLat()).isEqualTo(37.59);
    }
    @Test void 빈문장과_긴문장은_원문을_바꾸지_않는다()throws Exception{
        var m=member("rmanageowner");long id=route(m,m).getId();
        for(String text:new String[]{"   ","a".repeat(201)}){
            mvc.perform(post("/api/routes/"+id+"/points/1/guide").with(user(m.getUsername())).with(csrf()).contentType("application/json").content("{\"text\":\""+text+"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("error").value("안내문은 1~200자로 써 주세요."));
            assertThat(storedPoint(id).getGuideText()).isEqualTo("횡단보도 앞에서 멈춰서 신호와 양쪽을 확인해요.");
        }
    }
    @Test void 횡단_안전_안내가_누락되거나_신호를_단정하면_원문을_보존한다() throws Exception {
        var m = member("rmanageowner");
        long id = route(m, m).getId();
        for (String invalid : java.util.List.of("신호와 양쪽을 확인해요.", "멈춰서 양쪽을 확인해요.",
                "멈춰서 신호를 확인해요.", "멈춰서 초록불 신호와 양쪽을 확인해요.")) {
            mvc.perform(post("/api/routes/" + id + "/points/1/guide")
                    .with(user(m.getUsername())).with(csrf()).contentType("application/json")
                    .content("{\"text\":\"" + invalid + "\"}"))
                    .andExpect(status().isBadRequest());
            var point = storedPoint(id);
            assertThat(point.getGuideText()).isEqualTo("횡단보도 앞에서 멈춰서 신호와 양쪽을 확인해요.");
            assertThat(point.isGuideEdited()).isFalse();
        }
    }
    @Test void 다른가족은_수정삭제할수없고_없는지점도_수정하지_않는다()throws Exception{
        var m=member("rmanageowner");var other=member("rmanageother");long id=route(m,m).getId();
        mvc.perform(post("/api/routes/"+id+"/points/1/guide").with(user(other.getUsername())).with(csrf()).contentType("application/json").content("{\"text\":\"변경\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/routes/"+id+"/delete").with(user(other.getUsername())).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/routes/"+id+"/points/999/guide").with(user(m.getUsername())).with(csrf()).contentType("application/json").content("{\"text\":\"변경\"}"))
            .andExpect(status().isNotFound());assertThat(storedPoint(id).getGuideText()).isEqualTo("횡단보도 앞에서 멈춰서 신호와 양쪽을 확인해요.");
    }
    @Test void 보호자삭제는_경로지점도_지우고_통학기록은_남긴다()throws Exception{
        var parent=member("rmanageparent");var child=member("rmanagechild");links.saveAndFlush(new GuardianLink(parent,child,GuardianLink.Type.CREATED));
        var r=route(child,parent);long id=r.getId();long pointId=r.getPoints().get(0).getId();long tripId=trips.saveAndFlush(new Trip(child,id,"검수길",600)).getId();
        mvc.perform(post("/routes/"+id+"/delete").with(user(parent.getUsername())).with(csrf()))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/children/"+child.getId()));
        em.flush();em.clear();assertThat(routes.findById(id)).isEmpty();assertThat(em.find(RoutePoint.class,pointId)).isNull();
        assertThat(trips.findById(tripId)).isPresent();
    }
}
