package com.safeschoolroad.ops;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class ReadinessTests {
 @Test void 정상_상태만_반환하고_반복_호출로_DB를_계속_조회하지_않는다(){
  var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject("SELECT 1",Integer.class)).thenReturn(1);var c=new ReadinessController(jdbc);
  assertThat(c.ready().getStatusCode().value()).isEqualTo(200);assertThat(c.ready().getBody()).containsOnlyKeys("status");verify(jdbc,times(1)).queryForObject("SELECT 1",Integer.class);
 }
 @Test void 장애_응답에는_연결정보와_예외를_노출하지_않는다(){
  var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject("SELECT 1",Integer.class)).thenThrow(new IllegalStateException("sensitive"));var c=new ReadinessController(jdbc);
  assertThat(c.ready().getStatusCode().value()).isEqualTo(503);assertThat(c.ready().getBody()).containsEntry("status","DOWN").hasSize(1);
 }
}
