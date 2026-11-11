package com.safeschoolroad.external;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class NaverSearchClientTests {
    @Test void 소수와_정수형_WGS84를_같은_좌표로_해석한다(){
        assertThat(NaverSearchClient.coordinate("1270800000")).isEqualTo(127.08);
        assertThat(NaverSearchClient.coordinate("375900000")).isEqualTo(37.59);
        assertThat(NaverSearchClient.coordinate("127.08")).isEqualTo(127.08);
        assertThat(NaverSearchClient.coordinate("bad")).isNaN();
    }
    @Test void 서울_밖이나_잘못된_좌표는_제외하고_강조태그를_제거한다() throws Exception {
        var mapper=new ObjectMapper();
        var valid=mapper.readTree("{\"title\":\"<b>면목</b>초등학교\",\"mapx\":\"1270800000\",\"mapy\":\"375900000\",\"address\":\"서울\"}");
        assertThat(NaverSearchClient.localItem(valid).title()).isEqualTo("면목초등학교");
        assertThat(NaverSearchClient.localItem(mapper.readTree("{\"mapx\":\"129\",\"mapy\":\"35\"}"))).isNull();
        assertThat(NaverSearchClient.localItem(mapper.readTree("{}"))).isNull();
    }
}
