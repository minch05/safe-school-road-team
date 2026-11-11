package com.safeschoolroad.facility;
import com.safeschoolroad.facility.load.FacilityRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest @Transactional
class SchoolCachePersistenceTests {
 @Autowired JdbcTemplate jdbc;
 @Autowired FacilityRepository facilities;
 @Test void DB학교좌표가바뀌면_캐시만료후_보호구역판정이갱신된다(){
  String id=UUID.randomUUID().toString().substring(0,8),name="검수"+id+"초등학교",source="CACHE_TEST_"+id;
  facilities.insertAll(List.of(new FacilityRow(FacilityKind.SCHOOL,name,37.59,127.08,null,"중랑구",source,"one",null)));
  var time=new AtomicLong();var check=new SchoolZoneCheck(jdbc,time::get);
  var zone=new Facility(999999,FacilityKind.SCHOOL_ZONE,name,37.59,127.08,null,"CACHE_TEST","zone");
  assertThat(check.misplaced(zone)).isFalse();
  jdbc.update("UPDATE facility SET lat=? WHERE source=?",37.60,source);
  time.set(SchoolZoneCheck.CACHE_NANOS-1);assertThat(check.misplaced(zone)).isFalse();
  time.set(SchoolZoneCheck.CACHE_NANOS);assertThat(check.misplaced(zone)).isTrue();
  jdbc.update("UPDATE facility SET lat=? WHERE source=?",37.59,source);
  check.reload();assertThat(check.misplaced(zone)).isFalse();
 }
}
