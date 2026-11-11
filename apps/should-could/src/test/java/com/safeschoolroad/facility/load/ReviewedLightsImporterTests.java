package com.safeschoolroad.facility.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 구청 보안등 후보 파일 검증: 하나라도 틀리면 아무것도 넣지 않는다
class ReviewedLightsImporterTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode file(String rows) throws Exception {
        return json.readTree("{\"version\":1,\"districts\":[\"중랑구\",\"강동구\",\"송파구\",\"서울\",\"분당구\"],\"facilities\":[" + rows + "]}");
    }

    private static String row(String id, String district, double lat, String date) {
        return "{\"kind\":\"SECURITY_LIGHT\",\"source\":\"SECURITY_LIGHT_DISTRICT\",\"sourceId\":\"" + id
                + "\",\"name\":\"보안등 1\",\"lat\":" + lat + ",\"lng\":127.08,\"district\":\"" + district
                + "\",\"baseDate\":\"" + date + "\"}";
    }

    @Test
    void 올바른_파일은_통과() throws Exception {
        var rows = ReviewedLightsImporter.validate(file(row("a1", "중랑구", 37.59, "2026-02-26") + "," + row("a2", "강동구", 37.55, "2026-09-11")), TODAY);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).district()).isEqualTo("중랑구");
    }

    @Test
    void 같은_식별자가_두_번이면_거절() {
        assertThatThrownBy(() -> ReviewedLightsImporter.validate(file(row("a1", "중랑구", 37.59, "2026-02-26") + "," + row("a1", "중랑구", 37.59, "2026-02-26")), TODAY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 서울_밖_좌표나_2년_넘은_기준일은_거절() {
        assertThatThrownBy(() -> ReviewedLightsImporter.validate(file(row("a1", "중랑구", 36.5, "2026-02-26")), TODAY))
                .hasMessageContaining("좌표");
        assertThatThrownBy(() -> ReviewedLightsImporter.validate(file(row("a1", "송파구", 37.5, "2020-01-01")), TODAY))
                .hasMessageContaining("기준일");
    }

    @Test
    void 구_이름이_아니거나_서울_전체면_거절() {
        assertThatThrownBy(() -> ReviewedLightsImporter.validate(file(row("a1", "서울", 37.5, "2026-02-26")), TODAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReviewedLightsImporter.validate(file(row("a1", "분당구", 37.5, "2026-02-26")), TODAY))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void 일부구_갱신은_그구만_교체하고_다른구를_삭제하지_않는다() {
        var facilities = org.mockito.Mockito.mock(com.safeschoolroad.facility.FacilityRepository.class);
        var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var importer = new ReviewedLightsImporter(facilities, jdbc,
                org.mockito.Mockito.mock(org.springframework.transaction.support.TransactionTemplate.class),
                org.mockito.Mockito.mock(org.springframework.context.ConfigurableApplicationContext.class));
        importer.deleteDistrictRows(java.util.Set.of("중랑구"));
        org.mockito.Mockito.verify(facilities).deleteSourceRegion("SECURITY_LIGHT_DISTRICT", "중랑구");
        org.mockito.Mockito.verifyNoMoreInteractions(facilities);
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }
}
