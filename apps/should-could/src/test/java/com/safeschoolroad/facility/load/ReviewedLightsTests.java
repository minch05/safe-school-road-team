package com.safeschoolroad.facility.load;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;
class ReviewedLightsTests {
 private final ObjectMapper mapper=new ObjectMapper();
 private String file(String date,String district,String kind){return """
 {"version":1,"districts":["은평구"],"facilities":[{"kind":"%s","source":"SECURITY_LIGHT_DISTRICT","sourceId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","district":"%s","name":"등1","lat":37.6,"lng":126.9,"baseDate":"%s"}]}
 """.formatted(kind,district,date);}
 @Test void 날짜와_지역을_검증하고_다른_시설을_거부한다()throws Exception{
  var today=LocalDate.of(2026,10,10);
  assertThat(ReviewedLightsImporter.validate(mapper.readTree(file("2026-06-30","은평구","SECURITY_LIGHT")),today)).hasSize(1);
  for(String text:new String[]{file("2020-01-01","은평구","SECURITY_LIGHT"),file("2026-10-11","은평구","SECURITY_LIGHT"),file("2026-06-30","서대문구","SECURITY_LIGHT"),file("2026-06-30","은평구","CCTV")})
   assertThatThrownBy(()->ReviewedLightsImporter.validate(mapper.readTree(text),today)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void 비어있는_지역으로_기존자료를_삭제하지_않는다()throws Exception{
  assertThatThrownBy(()->ReviewedLightsImporter.validate(mapper.readTree("{\"version\":1,\"districts\":[\"은평구\"],\"facilities\":[]}"),LocalDate.of(2026,10,10))).isInstanceOf(IllegalArgumentException.class);
 }
}
