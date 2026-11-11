package com.safeschoolroad.facility.load;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class PublicDataBackupTests {
 @TempDir Path dir;
 @Test void 저장파일은_자료와_일치하는_해시를_보관한다() throws Exception {
  var backup=new PublicDataBackup();ReflectionTestUtils.setField(backup,"directory",dir.toString());
  var file=backup.write(Map.of("facilities",List.of(Map.of("name","공개시설"))));
  assertThat(Files.readString(file)).contains("공개시설");
  var hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
  assertThat(Files.readString(Path.of(file+".sha256"))).isEqualTo(hash);
 }
 @Test void 디렉터리오류는_성공으로_숨기지않는다() throws Exception {
  var blocked=dir.resolve("file");Files.writeString(blocked,"x");var backup=new PublicDataBackup();ReflectionTestUtils.setField(backup,"directory",blocked.toString());
  assertThatThrownBy(()->backup.write(Map.of())).isInstanceOf(IllegalStateException.class).hasMessageContaining("기존 자료를 변경하지");
 }
}
