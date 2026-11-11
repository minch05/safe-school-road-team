package com.safeschoolroad.facility.load;

import com.safeschoolroad.facility.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @Transactional
class FacilityBackupPersistenceTests {
 @Autowired FacilityRepository facilities;
 @TempDir Path dir;
 String source="BACKUP_TEST_"+UUID.randomUUID().toString().substring(0,8);
 FacilityRow row(String source,String district,String id){return new FacilityRow(FacilityKind.CROSSWALK,"합성시설",37.59,127.08,SignalStatus.YES,district,source,id,LocalDate.of(2026,10,1));}
 @Test void 백업조회는_교체출처와_구만_포함한다(){
  facilities.insertAll(List.of(row(source,"중랑구","one"),row(source,"서대문구","two"),row(source+"x","중랑구","other")));
  var snapshot=facilities.snapshotSourceRegion(source,"중랑구");
  assertThat(snapshot).hasSize(1);assertThat(snapshot.get(0)).containsEntry("source_id","one").containsEntry("base_date","2026-10-01");
  assertThat(facilities.snapshotSourceRegion(source,"서울")).hasSize(2);
 }
 @Test void JSON백업을_읽어_합성시설의_값을_복원할수있다()throws Exception{
  facilities.insertAll(List.of(row(source,"중랑구","one")));
  var before=facilities.snapshotSourceRegion(source,"중랑구");
  var backup=new PublicDataBackup();ReflectionTestUtils.setField(backup,"directory",dir.toString());
  var file=backup.write(Map.of("facilities",before));
  assertThat(facilities.deleteSourceRegion(source,"중랑구")).isEqualTo(1);
  var node=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readAllBytes(file)).path("facilities").get(0);
  facilities.insertAll(List.of(new FacilityRow(FacilityKind.valueOf(node.path("kind").asText()),node.path("name").asText(),node.path("lat").asDouble(),node.path("lng").asDouble(),SignalStatus.valueOf(node.path("signal_status").asText()),node.path("district").asText(),node.path("source").asText(),node.path("source_id").asText(),LocalDate.parse(node.path("base_date").asText()))));
  assertThat(facilities.snapshotSourceRegion(source,"중랑구")).isEqualTo(before);
 }
}
