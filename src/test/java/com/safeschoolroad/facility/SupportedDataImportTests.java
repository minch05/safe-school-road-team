package com.safeschoolroad.facility;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

// 다른 시험(표본 10건 확인)과 섞이지 않게 별도 메모리 DB를 쓴다.
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:supported_import;MODE=MySQL;DB_CLOSE_DELAY=-1")
class SupportedDataImportTests {
    @Autowired SupportedDataImporter importer;
    @Autowired JdbcTemplate jdbc;
    @Autowired FacilityApi api;

    @Test void importsTwelveDistrictsIdempotentlyWithSourcesAndCoverage() throws Exception {
        assertEquals(0, api.status().get("crosswalkCount"));
        assertEquals("적재된 횡단보도 자료가 없습니다.", api.status().get("coverage"));
        importer.run("--import-supported-data");
        int facilities = count("SELECT COUNT(*) FROM facility");
        int areas = count("SELECT COUNT(*) FROM risk_area");
        var second = importer.importAll();
        assertEquals(facilities, second.facilities());
        assertEquals(areas, second.riskAreas());
        assertEquals(facilities, count("SELECT COUNT(*) FROM facility"), "다시 적재해도 행이 늘면 안 된다");
        assertEquals(areas, count("SELECT COUNT(*) FROM risk_area"));

        assertTrue(count("SELECT COUNT(*) FROM facility WHERE district='중랑구' AND kind='CROSSWALK'") > 1000);
        assertEquals(12, count("SELECT COUNT(DISTINCT district) FROM facility"));
        assertEquals(count("SELECT COUNT(*) FROM facility WHERE kind='CROSSWALK'"), api.status().get("crosswalkCount"));
        assertTrue(api.status().get("coverage").toString().contains("서울 12개 구"));
        assertFalse(api.status().get("coverage").toString().contains("개발용 표본"));
        assertEquals(0, count("SELECT COUNT(*) FROM facility WHERE source='' OR source_reference='' OR source_downloaded IS NULL"));
        // 신호는 확정 자료가 없다: 횡단보도는 추정 또는 미확인만
        assertEquals(List.of("ESTIMATED", "UNKNOWN"), jdbc.queryForList(
                "SELECT DISTINCT signal_status FROM facility WHERE kind='CROSSWALK' ORDER BY signal_status", String.class));
        assertEquals(0, count("SELECT COUNT(*) FROM facility WHERE kind<>'CROSSWALK' AND signal_status IS NOT NULL"));
        // 보안등은 12개 구 모두 자료 없음으로 기록
        assertEquals(12, count("SELECT COUNT(*) FROM data_coverage WHERE kind='SECURITY_LIGHT' AND status='NO_DATA' AND rows_loaded=0"));
        assertEquals(0, count("SELECT COUNT(*) FROM data_coverage WHERE kind='CROSSWALK' AND status<>'LOADED'"));
        assertTrue(areas > 0);
        assertEquals(0, count("SELECT COUNT(*) FROM risk_area WHERE min_lat > max_lat OR min_lng > max_lng"));
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }
}
