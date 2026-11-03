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

    @GetMapping("/status") Map<String, Object> status() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM facility WHERE kind='CROSSWALK'", Integer.class);
        return Map.of("crosswalkCount", count == null ? 0 : count,
                "coverage", "중랑구 개발용 표본 10건만 검증 가능. 정식 지원 범위가 아닙니다.");
    }

    @GetMapping("/sample") List<Map<String, Object>> sample() {
        return jdbc.query("SELECT original_id, lat, lng, source_downloaded, source_reference FROM facility WHERE kind='CROSSWALK' AND district='중랑구' ORDER BY original_id LIMIT 100",
                (rs, row) -> Map.of("originalId", rs.getString(1), "lat", rs.getDouble(2), "lng", rs.getDouble(3),
                        "sourceDownloaded", rs.getDate(4).toString(), "sourceReference", rs.getString(5)));
    }
}
