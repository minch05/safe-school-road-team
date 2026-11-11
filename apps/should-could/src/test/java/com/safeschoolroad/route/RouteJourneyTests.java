package com.safeschoolroad.route;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.external.TmapRoute;
import com.safeschoolroad.facility.*;
import com.safeschoolroad.family.FamilyService;
import com.safeschoolroad.geo.LatLng;
import com.safeschoolroad.guide.GuideService;
import com.safeschoolroad.member.*;
import com.safeschoolroad.route.analysis.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
/** 실제 로그인/세션/초안/DB 저장/안내/출발·도착 연결. 외부 탐색·AI 응답만 합성, DB 롤백. */
@SpringBootTest @AutoConfigureMockMvc @Transactional
class RouteJourneyTests {
 @Autowired MockMvc mvc; @Autowired MemberRepository members; @Autowired FamilyService family;
 @Autowired PasswordEncoder passwords; @Autowired RouteRepository routes; @Autowired ObjectMapper json;
 @MockitoBean RouteCandidateService candidates; @MockitoBean GuideService guides;
 Member adult(String name){return members.save(new Member(name,passwords.encode("password123"),"검증",true));}
 MockHttpSession login(Member m)throws Exception{
  var r=mvc.perform(post("/login").with(csrf()).param("username",m.getUsername()).param("password","password123")).andExpect(authenticated().withUsername(m.getUsername())).andReturn();
  return (MockHttpSession)r.getRequest().getSession();
 }
 void journey(Member creator,Member owner)throws Exception{
  var f=new Facility(999,FacilityKind.CROSSWALK,"합성 검수 지점",37.592,127.082,SignalStatus.UNKNOWN,"TEST","journey");
  var a=new RouteAnalysis(new TmapRoute(List.of(new LatLng(37.59,127.08),new LatLng(37.6,127.09)),List.of(),600,480),List.of(new RouteAnalysis.KeyPoint(f,PointReason.CROSSING,100,1)),1,0,1,0,List.of(),List.of(),0,0);
  when(candidates.find(any(),any())).thenReturn(List.of(new RouteCandidateService.Candidate(List.of(0),a,CrimeExposure.unknown("합성 검수"))));
  when(candidates.analyzeOption(any(),any(),anyInt())).thenReturn(a);
  when(guides.write(anyList(),any())).thenReturn(new GuideService.GuideResult(Map.of(1,"횡단보도 앞에서 멈춰요. 양쪽을 살펴요."),"TEMPLATE",0,List.of(),"-"));
  var session=login(creator);
  var body=new java.util.LinkedHashMap<String,Object>();body.put("start",Map.of("name","검수 출발","lat",37.59,"lng",127.08));body.put("end",Map.of("name","검수 도착","lat",37.6,"lng",127.09));
  mvc.perform(post("/api/routes/candidates").session(session).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isOk()).andExpect(jsonPath("$.candidates[0].crossings.unknown").value(1));
  body.put("option",0);body.put("ownerId",owner.getId());body.put("tone","LOWER");body.put("requestId","journey-draft");
  mvc.perform(post("/api/routes/draft").session(session).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isOk()).andExpect(jsonPath("$.draftId").value("journey-draft")).andExpect(jsonPath("$.points[0].text").value("횡단보도 앞에서 멈춰요. 양쪽을 살펴요."));
  String save="{\"name\":\"검수 통학길\",\"saveStart\":true,\"saveEnd\":true,\"draftId\":\"journey-draft\",\"requestId\":\"journey-save\"}";
  var result=mvc.perform(post("/api/routes").session(session).with(csrf()).contentType("application/json").content(save)).andExpect(status().isOk()).andReturn();
  long id=json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
  assertThat(routes.findById(id).orElseThrow().getOwner().getId()).isEqualTo(owner.getId());
  mvc.perform(post("/api/routes").session(session).with(csrf()).contentType("application/json").content(save)).andExpect(jsonPath("$.id").value(id));
  var walker=owner.getId().equals(creator.getId())?session:login(owner);
  mvc.perform(get("/routes/"+id+"/navigate").session(walker)).andExpect(status().isOk()).andExpect(view().name("navigate"));
  mvc.perform(get("/api/routes/"+id+"/points").session(walker)).andExpect(status().isOk()).andExpect(jsonPath("$[0].text").value("횡단보도 앞에서 멈춰요. 양쪽을 살펴요."));
  if(owner.getId().equals(creator.getId())){
   mvc.perform(post("/api/trips/start").session(walker).with(csrf()).contentType("application/json").content("{\"routeId\":"+id+",\"expectedSeconds\":480}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("보호자와 연동되지 않아 알림을 보내지 않아요."));
   mvc.perform(get("/routes/"+id+"/arrived").session(walker)).andExpect(status().isOk()).andExpect(model().attribute("recorded",false));return;
  }
  var start=mvc.perform(post("/api/trips/start").session(walker).with(csrf()).contentType("application/json").content("{\"routeId\":"+id+",\"expectedSeconds\":480,\"requestId\":\"00000000-0000-4000-8000-000000000001\"}")).andExpect(status().isOk()).andReturn();
  long trip=json.readTree(start.getResponse().getContentAsString()).path("tripId").asLong();
  mvc.perform(post("/api/trips/"+trip+"/arrive").session(walker).with(csrf()).contentType("application/json").content("{\"auto\":false}")).andExpect(status().isOk());
  mvc.perform(get("/routes/"+id+"/arrived?tripId="+trip).session(walker)).andExpect(status().isOk()).andExpect(model().attribute("recorded",true));
 }
 @Test void 개인_경로는_안내되지만_보호자없는_알림기록을_만들지_않는다()throws Exception{var m=adult("journeyadult");journey(m,m);}
 @Test void 보호자가_등록한_아이경로를_아이로그인으로_안내받는다()throws Exception{var p=adult("journeyparent");var c=family.createChild(p,"journeychild","password123","하늘");journey(p,c);}
}
