package com.safeschoolroad.guide;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.facility.FacilityKind;
import com.safeschoolroad.facility.SignalStatus;
import com.safeschoolroad.route.Route;
import com.safeschoolroad.route.analysis.PointReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 명시적으로 켰을 때만 무료 Gemini 호출. 계정·DB·TMAP·실제 경로를 사용하지 않는다. */
@EnabledIfEnvironmentVariable(named="RUN_LIVE_GEMINI_EVAL",matches="true")
class GeminiLiveEvaluationTests {
 @Test void 예비_모델의_소규모_안내문_평가() throws Exception {
  Properties settings=new Properties();try(var in=Files.newInputStream(Path.of(".env"))){settings.load(in);}
  String key=System.getenv().getOrDefault("LLM_API_KEY",settings.getProperty("LLM_API_KEY",""));
  assertThat(key.isBlank()).as("Gemini 키 설정 여부").isFalse();
  var points=List.of(
   new GuidePoint(1,FacilityKind.CROSSWALK,"",SignalStatus.ESTIMATED,PointReason.CROSSING,false),
   new GuidePoint(2,FacilityKind.EMERGENCY_BELL,"가로변 · 신길로",null,PointReason.TURN,false),
   new GuidePoint(3,FacilityKind.CCTV,"신길로",null,PointReason.GAP,false),
   new GuidePoint(4,FacilityKind.SCHOOL,"서울홍제초등학교",null,PointReason.TURN,false),
   new GuidePoint(5,FacilityKind.CROSSWALK,"",SignalStatus.UNKNOWN,PointReason.CROSSING,true));
  ObjectMapper json=new ObjectMapper();var results=new ArrayList<Map<String,Object>>();
  for(String model:List.of("gemini-3.5-flash","gemini-3.6-flash")) {
   var service=new GuideService(json,new LlmModels("gemini:"+model,"",key,"https://generativelanguage.googleapis.com/v1beta/openai"));
   for(Route.Tone tone:Route.Tone.values()) {
    long start=System.nanoTime();var result=service.write(points,tone);
    var row=new LinkedHashMap<String,Object>();row.put("model",model);row.put("tone",tone.name());row.put("source",result.source());row.put("attempts",result.attempts());row.put("seconds",Math.round((System.nanoTime()-start)/1e8)/10.0);row.put("errors",result.errors());row.put("texts",result.texts());row.put("finalValidationErrors",GuideValidator.validate(points,result.texts()));results.add(row);
    Path output=Path.of("build/ai-eval/gemini-live-review.json");Files.createDirectories(output.getParent());json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),Map.of("checkedAt",LocalDate.now(ZoneId.of("Asia/Seoul")).toString(),"scope","합성 공개 시설 5지점 · 실제 통학 경로 아님","results",results));
    assertThat(GuideValidator.validate(points,result.texts())).isEmpty();
    // 인증/한도/통신 실패는 다음 말투에서도 반복하지 않는다.
    if(result.errors().stream().anyMatch(e->e.contains("AI 호출 실패")))break;
    Thread.sleep(8000);
   }
  }
 }
}
