package com.safeschoolroad.facility.load;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class PedestrianLightStorageTests {
 @Test void 저장보행등은_중복을제거하고_원본좌표를유지하며_날짜와구를꾸미지않는다(){
  var lights=new PedestrianLights();lights.add(37.59,127.08);lights.add(37.59,127.08);lights.add(Double.NaN,127.08);
  var rows=lights.facilityRows();assertThat(rows).hasSize(1);var row=rows.get(0);
  assertThat(row.lat()).isEqualTo(37.59);assertThat(row.lng()).isEqualTo(127.08);
  assertThat(row.source()).isEqualTo("SEOUL_PED_SIGNAL");assertThat(row.sourceId()).isEqualTo("37.59:127.08");
  assertThat(row.baseDate()).isNull();assertThat(row.district()).isEmpty();assertThat(row.signalStatus()).isNull();
 }
 @Test void 파일순서가달라도_보행등저장순서와식별자는같다(){
  var first=new PedestrianLights();first.add(37.6,127.09);first.add(37.59,127.08);
  var second=new PedestrianLights();second.add(37.59,127.08);second.add(37.6,127.09);
  assertThat(first.facilityRows()).isEqualTo(second.facilityRows());
 }
}
