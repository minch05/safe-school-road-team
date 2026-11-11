package com.safeschoolroad.facility;

import com.safeschoolroad.facility.load.FacilityRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// facility 테이블. 공간 함수(MySQL)를 써야 해서 JPA 대신 SQL을 직접 쓴다.
// 좌표 문자열은 항상 "POINT(경도 위도)" 로 만들고 'axis-order=long-lat' 을 붙인다.
// (MySQL 8 은 SRID 4326 의 기본 순서가 위도·경도라서 순서를 밝혀 두지 않으면 뒤바뀐다)
@Repository
public class FacilityRepository {

    private static final int BATCH_SIZE = 1000;
    private static final String AXIS = "'axis-order=long-lat'";

    private final JdbcTemplate jdbc;

    public FacilityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** region 이 "서울"이면 전부, 아니면 그 구의 시설을 지운다. 지운 개수를 돌려준다. */
    public int deleteRegion(String region) {
        if (FacilityRegion.ALL_SEOUL.equals(region)) {
            return jdbc.update("DELETE FROM facility");
        }
        return jdbc.update("DELETE FROM facility WHERE district = ?", region);
    }

    public int deleteSourceRegion(String source, String region) {
        return FacilityRegion.ALL_SEOUL.equals(region)
                ? jdbc.update("DELETE FROM facility WHERE source = ?", source)
                : jdbc.update("DELETE FROM facility WHERE source = ? AND district = ?", source, region);
    }

    public List<Map<String,Object>> snapshotSourceRegion(String source,String region) {
        String sql="SELECT kind,name,lat,lng,signal_status,district,source,source_id,CAST(base_date AS CHAR) AS base_date FROM facility WHERE source = ?";
        return FacilityRegion.ALL_SEOUL.equals(region)?jdbc.queryForList(sql+" FOR UPDATE",source):jdbc.queryForList(sql+" AND district = ? FOR UPDATE",source,region);
    }

    public void insertAll(List<FacilityRow> rows) {
        String sql = "INSERT INTO facility (kind, name, lat, lng, location, signal_status, district, source, source_id, base_date, imported_at) "
                + "VALUES (?, ?, ?, ?, ST_GeomFromText(?, 4326, " + AXIS + "), ?, ?, ?, ?, ?, ?)";
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        for (int from = 0; from < rows.size(); from += BATCH_SIZE) {
            List<FacilityRow> batch = rows.subList(from, Math.min(from + BATCH_SIZE, rows.size()));
            jdbc.batchUpdate(sql, batch, batch.size(), (ps, r) -> {
                ps.setString(1, r.kind().name());
                ps.setString(2, r.name());
                ps.setDouble(3, r.lat());
                ps.setDouble(4, r.lng());
                ps.setString(5, point(r.lat(), r.lng()));
                ps.setString(6, r.signalStatus() == null ? null : r.signalStatus().name());
                ps.setString(7, r.district());
                ps.setString(8, r.source());
                ps.setString(9, r.sourceId());
                ps.setDate(10, r.baseDate() == null ? null : Date.valueOf(r.baseDate()));
                ps.setTimestamp(11, now);
            });
        }
    }

    /** 사각형(남·서·북·동) 안의 시설. 공간 인덱스를 쓴다. kinds 가 비어 있으면 모든 종류 */
    public List<Facility> findInBox(double south, double west, double north, double east, int limit,
                                    FacilityKind... kinds) {
        String box = String.format(Locale.ROOT, "POLYGON((%f %f, %f %f, %f %f, %f %f, %f %f))",
                west, south, east, south, east, north, west, north, west, south);
        StringBuilder sql = new StringBuilder(
                "SELECT id, kind, name, lat, lng, signal_status, source, source_id, base_date FROM facility "
                        + "WHERE MBRContains(ST_GeomFromText(?, 4326, " + AXIS + "), location)");
        List<Object> args = new ArrayList<>(List.of(box));
        if (kinds.length > 0) {
            sql.append(" AND kind IN (").append(String.join(",", java.util.Collections.nCopies(kinds.length, "?"))).append(")");
            for (FacilityKind k : kinds) {
                args.add(k.name());
            }
        }
        sql.append(" ORDER BY id LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(),
                (rs, i) -> new Facility(
                        rs.getLong("id"),
                        FacilityKind.valueOf(rs.getString("kind")),
                        rs.getString("name"),
                        rs.getDouble("lat"),
                        rs.getDouble("lng"),
                        rs.getString("signal_status") == null ? null : SignalStatus.valueOf(rs.getString("signal_status")),
                        rs.getString("source"),
                        rs.getString("source_id"), rs.getDate("base_date")==null?null:rs.getDate("base_date").toLocalDate()),
                args.toArray());
    }

    /** 종류별 개수 (적재 결과 확인용) */
    public List<Map<String, Object>> countByKind() {
        return new ArrayList<>(jdbc.queryForList(
                "SELECT kind, signal_status, COUNT(*) AS n FROM facility GROUP BY kind, signal_status ORDER BY kind, signal_status"));
    }

    /** 자료가 한 행이라도 있는 자치구. 완전한 수집 범위를 뜻하지 않는다. */
    public List<Map<String,Object>> coverageSummary() {
        return jdbc.queryForList("SELECT kind, GROUP_CONCAT(DISTINCT NULLIF(district,'') ORDER BY district SEPARATOR ',') AS districts, "
                +"CAST(MIN(base_date) AS CHAR) AS oldestDate, CAST(MAX(base_date) AS CHAR) AS newestDate, "
                +"SUM(base_date IS NULL) AS missingDateCount FROM facility GROUP BY kind");
    }

    private static String point(double lat, double lng) {
        return "POINT(" + lng + " " + lat + ")";
    }
}
