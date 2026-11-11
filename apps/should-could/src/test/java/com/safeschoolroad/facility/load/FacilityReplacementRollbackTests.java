package com.safeschoolroad.facility.load;
import com.safeschoolroad.facility.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest
class FacilityReplacementRollbackTests {
 @Autowired JdbcTemplate jdbc;
 @Autowired FacilityRepository actual;
 @Autowired TransactionTemplate tx;
 @TempDir Path dir;
 @Test void 재계산실패는_합성출처의_삭제와_새행삽입을_함께_되돌린다(){
  String prefix="T"+UUID.randomUUID().toString().substring(0,8);
  var scoped=new FacilityRepository(jdbc){
   @Override public int deleteSourceRegion(String source,String region){return super.deleteSourceRegion(prefix+source,region);}
   @Override public List<Map<String,Object>> snapshotSourceRegion(String source,String region){return super.snapshotSourceRegion(prefix+source,region);}
   @Override public void insertAll(List<FacilityRow> rows){super.insertAll(rows.stream().map(r->new FacilityRow(r.kind(),r.name(),r.lat(),r.lng(),r.signalStatus(),r.district(),prefix+r.source(),r.sourceId(),r.baseDate())).toList());}
  };
  var oldCross=new FacilityRow(FacilityKind.CROSSWALK,"이전",37.59,127.08,SignalStatus.UNKNOWN,"중랑구","SEOUL_CROSSWALK","old",null);
  var oldSignal=new FacilityRow(FacilityKind.PEDESTRIAN_SIGNAL,"",37.59,127.08,null,"","SEOUL_PED_SIGNAL","old",null);
  tx.executeWithoutResult(s->scoped.insertAll(List.of(oldCross,oldSignal)));
  try {
   var before=tx.execute(s->scoped.snapshotSourceRegion("SEOUL_CROSSWALK","서울"));
   var beforeSignals=tx.execute(s->scoped.snapshotSourceRegion("SEOUL_PED_SIGNAL","서울"));
   var backup=new PublicDataBackup();ReflectionTestUtils.setField(backup,"directory",dir.toString());
   var reestimate=mock(SignalReestimator.class);when(reestimate.refresh()).thenThrow(new IllegalStateException("합성 재계산 실패"));
   var importer=new FacilityImporter(scoped,tx,mock(ConfigurableApplicationContext.class),backup,reestimate);
   var replacement=new FacilityRow(FacilityKind.CROSSWALK,"새자료",37.6,127.09,SignalStatus.ESTIMATED,"중랑구","SEOUL_CROSSWALK","new",null);
   assertThatThrownBy(()->importer.replaceBase("중랑구",List.of(replacement),List.of(oldSignal))).hasMessage("합성 재계산 실패");
   var after=tx.execute(s->scoped.snapshotSourceRegion("SEOUL_CROSSWALK","서울"));assertThat(after).isEqualTo(before);
   var afterSignals=tx.execute(s->scoped.snapshotSourceRegion("SEOUL_PED_SIGNAL","서울"));assertThat(afterSignals).isEqualTo(beforeSignals);
   verify(reestimate).refresh();
  } finally {
   tx.executeWithoutResult(s->{actual.deleteSourceRegion(prefix+"SEOUL_CROSSWALK","서울");actual.deleteSourceRegion(prefix+"SEOUL_PED_SIGNAL","서울");});
  }
 }
}
