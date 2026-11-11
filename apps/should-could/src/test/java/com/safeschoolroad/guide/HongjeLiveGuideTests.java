package com.safeschoolroad.guide;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.external.VworldSearchClient;
import com.safeschoolroad.geo.LatLng;
import com.safeschoolroad.route.Route;
import com.safeschoolroad.route.RouteCandidateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 실제 공개 장소 탐색+시설 분석+AI 안내. 계정·사용자 경로 저장 없음. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named="RUN_LIVE_HONGJE_GUIDE",matches="true")
class HongjeLiveGuideTests {
 @Autowired VworldSearchClient search;
 @Autowired RouteCandidateService candidates;
 @Autowired GuideService guides;
 @Test void 홍제역에서_홍제초_안내문을_두_말투로_점검한다()throws Exception {
  var start=search.search("홍제역").stream().filter(p->p.title().contains("홍제역")).findFirst().orElseThrow();
  var end=search.search("서울홍제초등학교정문").stream().filter(p->p.title().contains("홍제초등학교정문")).findFirst().orElseThrow();
  var analysis=candidates.analyzeOption(new LatLng(start.lat(),start.lng()),new LatLng(end.lat(),end.lng()),0);
  var keyPoints=analysis.keyPoints();assertThat(keyPoints).isNotEmpty();
  var points=new ArrayList<GuidePoint>();
  for(int i=0;i<keyPoints.size();i++){var k=keyPoints.get(i);points.add(new GuidePoint(i+1,k.facility().kind(),k.facility().name(),k.facility().signalStatus(),k.reason(),i==keyPoints.size()-1));}
  var results=new ArrayList<Map<String,Object>>();
  for(Route.Tone tone:Route.Tone.values()){
   long startTime=System.nanoTime();var result=guides.write(points,tone);var errors=GuideValidator.validate(points,result.texts());
   var row=new LinkedHashMap<String,Object>();row.put("tone",tone.name());row.put("source",result.source());row.put("model",result.model());row.put("attempts",result.attempts());row.put("seconds",Math.round((System.nanoTime()-startTime)/1e8)/10.0);row.put("errors",result.errors());row.put("finalValidationErrors",errors);
   var publicPoints=new ArrayList<Map<String,Object>>();
   for(var p:points)publicPoints.add(Map.of("seq",p.seq(),"kind",p.kind().name(),"name",p.name(),"reason",p.reason().name(),"text",result.texts().get(p.seq())));
   row.put("points",publicPoints);results.add(row);
   Path out=Path.of("build/ai-eval/hongje-live-guide.json");Files.createDirectories(out.getParent());
   new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.toFile(),Map.of("checkedAt",LocalDate.now(ZoneId.of("Asia/Seoul")).toString(),"scenario","공개 장소 홍제역 → 홍제초 정문 검색 POI","crossingCount",analysis.crossingCount(),"crossingsUnknown",analysis.crossingsUnknown(),"results",results,"limits",List.of("검색 정문 POI는 실제 출입 가능 여부 확인 아님","실제 GPS 이동/시설 작동 검증 아님","공공시설 이름·문장만 기록 · TMAP 선/좌표/거리/시간 미저장","당일 API 캐시 및 한도 기록 외 DB 저장 없음")));
   if(result.source().equals("AI"))assertThat(errors).isEmpty();
   else assertThat(errors).allMatch(e->e.contains("똑같아요"));
   if(result.errors().stream().anyMatch(e->e.contains("AI 호출 실패")))break;
   Thread.sleep(8000);
  }
 }
}
