package com.safeschoolroad.facility.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class ReviewedDataTests {
    @TempDir Path dir;
    @Test void 최신_좌표가_없거나_폐기됐으면_과거_좌표를_되살리지_않는다() throws Exception {
        Files.writeString(dir.resolve(PublicDataFiles.CROSSWALK_FILE),"""
          {"downloaded":"2026-10-02","records":[
          {"CRSWK_MNG_NO1":"missing","HSTRY_ID":"1","STTS":"001","XCRD":"200000","YCRD":"550000"},
          {"CRSWK_MNG_NO1":"missing","HSTRY_ID":"2","STTS":"001","XCRD":"","YCRD":""},
          {"CRSWK_MNG_NO1":"retired","HSTRY_ID":"1","STTS":"001","XCRD":"200000","YCRD":"550000"},
          {"CRSWK_MNG_NO1":"retired","HSTRY_ID":"2","STTS":"999","XCRD":"","YCRD":""}
          ]}
          """);
        assertThat(new PublicDataFiles(dir).readCrosswalks(new PedestrianLights())).isEmpty();
    }
    @Test void 중복_보행등과_범위밖_좌표를_제외하고_10m_기준을_유지한다() {
        var p=new PedestrianLights();p.add(37.59,126.94);p.add(37.59,126.94);
        p.add(Double.NaN,126.94);p.add(0,0);
        assertThat(p.size()).isEqualTo(1);
        assertThat(p.anyWithin(37.59+9.0/110540,126.94,10)).isTrue();
        assertThat(p.anyWithin(37.59+11.0/110540,126.94,10)).isFalse();
    }
    @Test void 갱신_자료가_일부만_있으면_거부한다() throws Exception {
        var root=new ObjectMapper().readTree("{\"version\":1,\"facilities\":[],\"risks\":[]}");
        assertThatThrownBy(()->ReviewedDataImporter.validate(root)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("누락");
    }
    @Test void 허용하지_않은_원본과_확정_신호를_거부한다() throws Exception {
        for(String source:new String[]{"CCTV_STD","SEOUL_CROSSWALK"}) {
            var root=new ObjectMapper().readTree("""
              {"version":1,"facilities":[{"source":"%s","sourceId":"1","kind":"CROSSWALK","district":"서대문구","lat":37.59,"lng":126.94,"signalStatus":"YES"}],"risks":[]}
              """.formatted(source));
            assertThatThrownBy(()->ReviewedDataImporter.validate(root)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
