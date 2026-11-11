package com.safeschoolroad.facility;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class DataInfoTests {
 @Test void 중복구와_미상지역을_확보구로_부풀리지_않으며_빈종류도_공개한다(){
  FacilityRepository repo=mock(FacilityRepository.class);
  when(repo.coverageSummary()).thenReturn(List.of(Map.of("kind","SECURITY_LIGHT","districts","은평구, 은평구,미상","missingDateCount",3)));
  when(repo.countByKind()).thenReturn(List.of(Map.of("kind","SECURITY_LIGHT","n",10L)));
  var model=new ExtendedModelMap();assertThat(new DataInfoController(repo,mock(SchoolZoneCheck.class)).info(model)).isEqualTo("data-info");
  @SuppressWarnings("unchecked") var sources=(List<Map<String,Object>>)model.get("sources");
  assertThat(sources).hasSize(8);
  var light=sources.stream().filter(x->x.get("name").equals("보안등")).findFirst().orElseThrow();
  assertThat(light.get("districtCount")).isEqualTo(1);assertThat(light.get("missingDistricts").toString()).contains("서대문구").doesNotContain("은평구");
  var school=sources.stream().filter(x->x.get("name").equals("학교")).findFirst().orElseThrow();
  assertThat(school.get("districtCount")).isEqualTo(0);assertThat(school.get("count")).isEqualTo(0L);assertThat(school.get("date")).isEqualTo("미확인");
 }
}
