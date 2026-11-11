package com.safeschoolroad.facility.load;
import com.safeschoolroad.facility.RiskAreaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest @Transactional
class RiskBackupPersistenceTests {
 @Autowired RiskAreaRepository risks;
 @TempDir Path dir;
 @Test void 사고백업은_종류별로_영역과_기준연도를_복원한다()throws Exception{
  String kind="BACKUP_"+UUID.randomUUID().toString().substring(0,8),other=kind+"X";
  String area="{\"type\":\"Polygon\",\"coordinates\":[[[127.08,37.59],[127.081,37.59],[127.081,37.591],[127.08,37.591],[127.08,37.59]]]}";
  risks.insert(kind,"합성사고",area,"중랑구","BACKUP_TEST","one",2025);
  risks.insert(other,"다른종류",area,"중랑구","BACKUP_TEST","two",2024);
  var before=risks.snapshotKind(kind);assertThat(before).hasSize(1);assertThat(before.get(0)).containsEntry("base_year",2025);
  var backup=new PublicDataBackup();ReflectionTestUtils.setField(backup,"directory",dir.toString());
  var file=backup.write(Map.of("risks",before));assertThat(risks.deleteKind(kind)).isEqualTo(1);
  var node=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readAllBytes(file)).path("risks").get(0);
  risks.insert(node.path("kind").asText(),node.path("name").asText(),node.path("area_json").asText(),node.path("district").asText(),node.path("source").asText(),node.path("source_id").asText(),node.path("base_year").asInt());
  assertThat(risks.snapshotKind(kind)).isEqualTo(before);assertThat(risks.snapshotKind(other)).hasSize(1);
 }
}
