package com.safeschoolroad.facility;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

// risk_area 테이블 (사고다발지역처럼 영역으로 나오는 공공데이터)
@Repository
public class RiskAreaRepository {

    private final JdbcTemplate jdbc;

    public RiskAreaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record RiskArea(long id, String kind, String name, String geoJson, Integer baseYear) {
        public RiskArea(long id, String kind, String name, String geoJson) { this(id,kind,name,geoJson,null); }
    }

    public int deleteKind(String kind) {
        return jdbc.update("DELETE FROM risk_area WHERE kind = ?", kind);
    }

    public List<java.util.Map<String,Object>> snapshotKind(String kind) {
        return jdbc.queryForList("SELECT kind,name,area_json,district,source,source_id,base_year FROM risk_area WHERE kind = ? FOR UPDATE",kind);
    }

    // GeoJSON 은 항상 경도·위도 순서라 ST_GeomFromGeoJSON 이 4326 축 순서를 알아서 맞춘다
    public void insert(String kind, String name, String geoJson, String district, String source, String sourceId,
                       Integer baseYear) {
        jdbc.update("INSERT INTO risk_area (kind, name, area, area_json, district, source, source_id, base_year, imported_at) "
                        + "VALUES (?, ?, ST_GeomFromGeoJSON(?, 1, 4326), ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE name = VALUES(name), area = VALUES(area), area_json = VALUES(area_json), "
                        + "base_year = VALUES(base_year), imported_at = VALUES(imported_at)",
                kind, name, geoJson, geoJson, district, source, sourceId, baseYear,
                Timestamp.valueOf(LocalDateTime.now()));
    }

    /** 사각형(남·서·북·동)과 겹치는 영역 */
    public List<RiskArea> findInBox(double south, double west, double north, double east) {
        String box = String.format(Locale.ROOT, "POLYGON((%f %f, %f %f, %f %f, %f %f, %f %f))",
                west, south, east, south, east, north, west, north, west, south);
        return jdbc.query("SELECT id, kind, name, area_json, base_year FROM risk_area "
                        + "WHERE MBRIntersects(ST_GeomFromText(?, 4326, 'axis-order=long-lat'), area)",
                (rs, i) -> new RiskArea(rs.getLong("id"), rs.getString("kind"), rs.getString("name"),
                        rs.getString("area_json"), rs.getObject("base_year",Integer.class)),
                box);
    }

    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM risk_area", Integer.class);
        return n == null ? 0 : n;
    }
}
