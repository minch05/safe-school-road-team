package com.safeschoolroad.facility.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 구청 비상벨 후보 파일 검증과 표준데이터와 겹치는 벨 빼기
class ReviewedBellsImporterTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode file(String rows) throws Exception {
        return json.readTree("{\"version\":1,\"districts\":[\"종로구\"],\"facilities\":[" + rows + "]}");
    }

    private static String row(String id, String kind, String district, double lat, String date) {
        return "{\"kind\":\"" + kind + "\",\"source\":\"BELL_DISTRICT\",\"sourceId\":\"" + id
                + "\",\"name\":\"도로 · 북촌로\",\"lat\":" + lat + ",\"lng\":126.98,\"district\":\"" + district
                + "\",\"baseDate\":\"" + date + "\"}";
    }

    @Test
    void 올바른_파일은_통과하고_4년이_넘거나_다른_구나_종류면_거절() throws Exception {
        assertThat(ReviewedBellsImporter.validate(file(row("b1", "EMERGENCY_BELL", "종로구", 37.58, "2023-08-31")), TODAY)).hasSize(1);
        assertThatThrownBy(() -> ReviewedBellsImporter.validate(file(row("b1", "EMERGENCY_BELL", "종로구", 37.58, "2021-03-24")), TODAY))
                .hasMessageContaining("4년");
        assertThatThrownBy(() -> ReviewedBellsImporter.validate(file(row("b1", "EMERGENCY_BELL", "중구", 37.58, "2023-08-31")), TODAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReviewedBellsImporter.validate(file(row("b1", "CCTV", "종로구", 37.58, "2023-08-31")), TODAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReviewedBellsImporter.validate(file(row("b1", "EMERGENCY_BELL", "종로구", 36.0, "2023-08-31")), TODAY))
                .hasMessageContaining("서울 좌표");
    }

    @Test
    void 표준데이터_벨과_15m_안이면_같은_벨로_보고_뺀다() throws Exception {
        var rows = ReviewedBellsImporter.validate(file(row("near", "EMERGENCY_BELL", "종로구", 37.58005, "2023-08-31") + ","
                + row("far", "EMERGENCY_BELL", "종로구", 37.581, "2023-08-31")), TODAY);
        var kept = ReviewedBellsImporter.withoutSameBells(rows, List.of(new double[]{37.58, 126.98}));
        assertThat(kept).extracting(FacilityRow::sourceId).containsExactly("far"); // near 는 약 5.5m, far 는 약 110m
    }
}
