package com.safeschoolroad.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 근거 분석에 쓰는 DB 조회. 경로를 감싼 사각형 안의 시설·사고 다발 지역만 읽는다. */
@Repository
public class EvidenceData {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public EvidenceData(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record Facility(long id, String kind, String name, String district, double lat, double lng,
                           String signalStatus, String source, String originalId, String baseDate) { }

    public record Area(long id, String kind, String name, String district, Integer baseYear, String source,
                       String originalId, List<double[]> ring) { }

    public record Coverage(String district, String kind, int rows, String status, String source, String downloaded) { }

    public List<Facility> facilities(double[] box, List<String> kinds, int limit) {
        if (kinds.isEmpty()) return List.of();
        String marks = String.join(",", Collections.nCopies(kinds.size(), "?"));
        List<Object> args = new ArrayList<>(List.of(box[0], box[2], box[1], box[3]));
        args.addAll(kinds);
        args.add(Math.max(1, Math.min(limit, 50_000)));
        return jdbc.query("SELECT id, kind, name, district, lat, lng, signal_status, source, original_id, base_date FROM facility "
                        + "WHERE lat BETWEEN ? AND ? AND lng BETWEEN ? AND ? AND kind IN (" + marks + ") ORDER BY id LIMIT ?",
                (rs, i) -> new Facility(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getDouble(5),
                        rs.getDouble(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getDate(10) == null ? null : rs.getDate(10).toString()), args.toArray());
    }

    public List<Area> areas(double[] box) {
        return jdbc.query("SELECT id, kind, name, district, base_year, source, original_id, polygon_json FROM risk_area "
                        + "WHERE min_lat <= ? AND max_lat >= ? AND min_lng <= ? AND max_lng >= ? ORDER BY id",
                (rs, i) -> new Area(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, Integer.class), rs.getString(6), rs.getString(7), ring(rs.getString(8))),
                box[2], box[0], box[3], box[1]);
    }

    public List<Coverage> coverage() {
        return jdbc.query("SELECT district, kind, rows_loaded, status, source, source_downloaded FROM data_coverage ORDER BY district, kind",
                (rs, i) -> new Coverage(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5),
                        rs.getDate(6) == null ? null : rs.getDate(6).toString()));
    }

    private List<double[]> ring(String json) {
        try {
            double[][] coords = mapper.readValue(json, double[][].class);
            return List.of(coords);
        } catch (Exception e) {
            return List.of();
        }
    }
}
