package com.safeschoolroad.facility.load;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

// 적재 코드의 변환 부분. 기대값은 사전 시험 코드(count.py)가 낸 값이다. DB 없이 돈다.
class PublicDataConversionTests {

    @Test
    void 좌표_변환이_파이썬_시험_코드와_같다() {
        assertLatLng(Tm5186.toWgs84(207953.656925401, 543639.028629576), 37.49217120342092, 127.089938206362);
        assertLatLng(Tm5186.toWgs84(197106.91249998199, 556253.11874465295), 37.60585366799359, 126.9672358760831);
        // 원점(200000, 600000)은 북위 38도, 동경 127도
        assertLatLng(Tm5186.toWgs84(200000, 600000), 38.0, 127.0);
    }

    @Test
    void 주소에서_구_이름() {
        assertThat(SeoulDistricts.fromAddress("서울특별시 중랑구 면목로 434 (면목동)")).isEqualTo("중랑구");
        assertThat(SeoulDistricts.fromAddress("서울시 종로구 보건소  화장실")).isEqualTo("종로구");
        assertThat(SeoulDistricts.fromAddress("", null, "서울특별시 종로구청")).isEqualTo("종로구");
        assertThat(SeoulDistricts.fromAddress("서울특별시 구로구 디지털로 1")).isEqualTo("구로구");
        assertThat(SeoulDistricts.fromAddress("서울특별시 중구 세종대로 110")).isEqualTo("중구");
        assertThat(SeoulDistricts.fromAddress("경기도 성남시 분당구 정자로 1")).isEmpty();
    }

    @Test
    void 구_코드() {
        assertThat(SeoulDistricts.byCode("260")).isEqualTo("중랑구");
        assertThat(SeoulDistricts.byCode("300")).isEqualTo("강북구"); // 횡단보도 데이터의 옛 코드
        assertThat(SeoulDistricts.byCode("")).isEmpty();
    }

    @Test
    void 도로_이름만_뽑는다() {
        assertThat(PublicDataFiles.roadName("서울특별시 종로구 자하문로 19길 36")).isEqualTo("자하문로");
        assertThat(PublicDataFiles.roadName("서울특별시 중랑구 면목로 434 (면목동)")).isEqualTo("면목로");
        assertThat(PublicDataFiles.roadName("서울특별시 종로구")).isEmpty();
    }

    @Test
    void CSV_따옴표와_칸_안의_쉼표() throws IOException {
        String csv = "﻿이름,주소,메모\n"
                + "a,\"서울, 중랑구\",\"말 \"\"인용\"\"\"\n"
                + "b,\"두 줄\n주소\",\n";
        List<Map<String, String>> rows = new ArrayList<>();
        SimpleCsv.forEachRow(new StringReader(csv), rows::add);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("이름", "a").containsEntry("주소", "서울, 중랑구")
                .containsEntry("메모", "말 \"인용\"");
        assertThat(rows.get(1)).containsEntry("주소", "두 줄\n주소").containsEntry("메모", "");
    }

    @Test
    void 보행등_10m_안이면_신호_있음_추정() {
        PedestrianLights lights = new PedestrianLights();
        lights.add(37.6000, 127.0800);
        assertThat(lights.anyWithin(37.60005, 127.0800, 10)).isTrue();   // 약 5.5m
        assertThat(lights.anyWithin(37.60015, 127.0800, 10)).isFalse();  // 약 16.6m
        lights.add(37.5000, 127.07999);                                  // 칸(0.001도) 경계 바로 왼쪽
        assertThat(lights.anyWithin(37.5000, 127.08005, 10)).isTrue();   // 경계 오른쪽 칸에서도 찾는다 (약 5.3m)
    }

    private static void assertLatLng(double[] actual, double lat, double lng) {
        assertThat(actual[0]).isCloseTo(lat, within(1e-9));
        assertThat(actual[1]).isCloseTo(lng, within(1e-9));
    }
}
