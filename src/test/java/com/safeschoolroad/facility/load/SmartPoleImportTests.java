package com.safeschoolroad.facility.load;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.facility.FacilityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class SmartPoleImportTests {
 @Test void 부분_자료는_스마트폴_원본만_바꾸고_기존_구청_보안등을_보존한다()throws Exception {
  var root=new ObjectMapper().readTree("""
  {"version":1,"districts":["서대문구"],"facilities":[{"kind":"SECURITY_LIGHT","source":"SMART_POLE_LIGHT","sourceId":"OA23001:341","name":"스마트폴 보안등","lat":37.58,"lng":126.94,"district":"서대문구","baseDate":"2025-12-31"}]}
  """);
  var rows=ReviewedLightsImporter.validate(root,LocalDate.of(2026,10,10),ReviewedLightsImporter.SMART_SOURCE);
  assertThatThrownBy(()->ReviewedLightsImporter.validate(root,LocalDate.of(2026,10,10))).isInstanceOf(IllegalArgumentException.class);
  var facilities=mock(FacilityRepository.class);var jdbc=mock(JdbcTemplate.class);
  var importer=new ReviewedLightsImporter(facilities,jdbc,mock(TransactionTemplate.class),mock(ConfigurableApplicationContext.class));
  importer.replaceSmartPoleRows(rows,Set.of("서대문구"));
  verify(facilities).deleteSourceRegion("SMART_POLE_LIGHT","서대문구");verify(facilities).insertAll(rows);
  verifyNoMoreInteractions(facilities);verifyNoInteractions(jdbc);
 }
}
