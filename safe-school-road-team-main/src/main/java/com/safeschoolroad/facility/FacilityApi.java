package com.safeschoolroad.facility;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/facilities")
public class FacilityApi {
    private final JdbcTemplate jdbc;
    public FacilityApi(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private record CoverageCounts(int crosswalks, int districts) { }

    @GetMapping("/status") Map<String, Object> status() {
        CoverageCounts counts = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM facility WHERE kind='CROSSWALK') AS crosswalks,
                       (SELECT COUNT(*) FROM data_coverage
                        WHERE kind='CROSSWALK' AND status='LOADED' AND rows_loaded>0) AS districts
                """, (rs, row) -> new CoverageCounts(rs.getInt("crosswalks"), rs.getInt("districts")));
        String coverage = "적재된 횡단보도 자료가 없습니다.";
        if (counts.districts() > 0) {
            coverage = "서울 " + counts.districts() + "개 구 횡단보도 자료 적재 · 지도에는 중랑구 일부만 표시합니다.";
        } else if (counts.crosswalks() > 0) {
            coverage = "중랑구 개발용 표본 " + counts.crosswalks() + "건 · 정식 지원 범위가 아닙니다.";
        }
        return Map.of("crosswalkCount", counts.crosswalks(), "coverage", coverage);
    }

    @GetMapping("/sample") List<Map<String, Object>> sample() {
        return jdbc.query("SELECT original_id, lat, lng, source_downloaded, source_reference FROM facility WHERE kind='CROSSWALK' AND district='중랑구' ORDER BY original_id LIMIT 100",
                (rs, row) -> Map.of("originalId", rs.getString(1), "lat", rs.getDouble(2), "lng", rs.getDouble(3),
                        "sourceDownloaded", rs.getDate(4).toString(), "sourceReference", rs.getString(5)));
    }
}
