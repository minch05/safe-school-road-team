package com.safeschoolroad.facility;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 공공 시설/사고 데이터의 집계만 조회한다. 회원·장소·경로·GPS는 읽거나 수정하지 않는다. */
@Service
public class DataQualityAudit {
    private final JdbcTemplate jdbc;
    public DataQualityAudit(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Report(LocalDate checkedAt, List<Map<String,Object>> facilities,
                         List<Map<String,Object>> districts, List<Map<String,Object>> accidents,
                         long coordinateMismatch, long outsideSeoulBounds, long unknownDistrict, List<String> missingKinds) {}
    @Transactional(readOnly = true)
    public Report inspect() {
        var facilities = jdbc.queryForList("""
            SELECT kind, COUNT(*) AS total, SUM(base_date IS NULL) AS missing_date,
                   COALESCE(SUM(base_date > CURRENT_DATE),0) AS future_date,
                   MIN(base_date) AS oldest_date, MAX(base_date) AS newest_date,
                   SUM(kind = 'CROSSWALK' AND (signal_status IS NULL OR signal_status = 'UNKNOWN')) AS unknown_signal
            FROM facility GROUP BY kind ORDER BY kind
            """);
        facilities.forEach(row -> row.put("dateMeaning", "CROSSWALK".equals(row.get("kind")) ? "원본 내려받은 날짜 · 현장 확인일 아님" : "제공 자료의 기준일 · 현장 확인일 아님"));
        // 서울의 누락 가능성을 종류/구별로 확인한다. 좌표나 시설 이름은 출력하지 않는다.
        var districts = jdbc.queryForList("SELECT kind, district, COUNT(*) AS total FROM facility GROUP BY kind, district ORDER BY kind, district");
        var accidents = jdbc.queryForList("SELECT kind, base_year, COUNT(*) AS total FROM risk_area GROUP BY kind, base_year ORDER BY kind, base_year");
        long mismatch = count("SELECT COUNT(*) FROM facility WHERE ABS(ST_Latitude(location)-lat)>0.000001 OR ABS(ST_Longitude(location)-lng)>0.000001");
        long outside = count("SELECT COUNT(*) FROM facility WHERE lat NOT BETWEEN 37.413 AND 37.715 OR lng NOT BETWEEN 126.734 AND 127.270");
        long noDistrict = count("SELECT COUNT(*) FROM facility WHERE district = ''");
        return new Report(LocalDate.now(), facilities, districts, accidents, mismatch, outside, noDistrict, java.util.Arrays.stream(FacilityKind.values()).filter(kind -> kind != FacilityKind.WALK_JUNCTION).filter(kind -> facilities.stream().noneMatch(row -> kind.name().equals(row.get("kind")))).map(FacilityKind::getLabel).toList());
    }
    private long count(String sql) { Long result = jdbc.queryForObject(sql, Long.class); return result == null ? 0 : result; }
}
