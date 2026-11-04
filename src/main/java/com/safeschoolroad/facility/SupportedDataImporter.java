package com.safeschoolroad.facility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지원 12개 구 공공자료 적재 (4-10-07). {@code --import-supported-data}로 실행할 때만 적재한다.
 * 자료 파일은 scripts/prepare_supported_data.py로 만든 src/main/resources/data/supported-districts.json.gz.
 * 같은 출처·원본 ID는 다시 적재해도 한 행으로 남는다.
 */
@Component
public class SupportedDataImporter implements CommandLineRunner {
    public static final String RESOURCE = "/data/supported-districts.json.gz";
    private static final int BATCH = 1000;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SupportedDataImporter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override public void run(String... args) throws Exception {
        if (List.of(args).contains("--import-supported-data")) {
            Result r = importAll();
            System.out.println("지원 지역 공공자료 적재: 시설 " + r.facilities() + "건, 사고 다발 지역 " + r.riskAreas()
                    + "개. 보안등·신호 확정 자료는 없습니다.");
        }
    }

    public record Result(int facilities, int riskAreas) { }

    @Transactional public Result importAll() throws Exception {
        JsonNode root;
        try (InputStream raw = getClass().getResourceAsStream(RESOURCE)) {
            if (raw == null) throw new IllegalStateException("지원 지역 자료 파일이 없습니다: " + RESOURCE);
            try (InputStream in = new GZIPInputStream(raw)) { root = mapper.readTree(in); }
        }
        Map<String, JsonNode> sources = new HashMap<>();
        root.path("sources").forEach(s -> sources.put(s.path("kind").asText(), s));

        List<Object[]> rows = new ArrayList<>();
        int facilities = 0;
        for (JsonNode f : root.path("facilities")) {
            JsonNode src = sources.get(f.path("kind").asText());
            if (src == null) throw new IllegalArgumentException("출처가 없는 자료 종류입니다: " + f.path("kind").asText());
            if (date(src.path("downloaded").asText(null)) == null)
                throw new IllegalArgumentException("원본 내려받은 날짜가 없는 출처입니다: " + src.path("source").asText());
            double lat = f.path("lat").asDouble(Double.NaN), lng = f.path("lng").asDouble(Double.NaN);
            if (!(lat >= 37.4 && lat <= 37.75 && lng >= 126.75 && lng <= 127.25))
                throw new IllegalArgumentException("서울 범위 밖 좌표입니다: " + f.path("originalId").asText());
            rows.add(new Object[]{src.path("source").asText(), f.path("originalId").asText(), f.path("kind").asText(),
                    f.path("district").asText(), lat, lng, date(src.path("downloaded").asText(null)),
                    src.path("reference").asText(), f.path("name").asText(""), textOrNull(f.path("signalStatus")),
                    date(f.path("baseDate").asText(null))});
            if (rows.size() == BATCH) { facilities += flushFacilities(rows); rows.clear(); }
        }
        facilities += flushFacilities(rows);

        String riskSource = root.path("riskSource").path("source").asText();
        int areas = 0;
        for (JsonNode a : root.path("riskAreas")) {
            double minLat = 90, maxLat = -90, minLng = 180, maxLng = -180;
            for (JsonNode c : a.path("polygon")) {
                double lng = c.get(0).asDouble(), lat = c.get(1).asDouble();
                minLat = Math.min(minLat, lat); maxLat = Math.max(maxLat, lat);
                minLng = Math.min(minLng, lng); maxLng = Math.max(maxLng, lng);
            }
            if (a.path("polygon").size() < 4) throw new IllegalArgumentException("다각형이 아닙니다: " + a.path("originalId").asText());
            jdbc.update("""
                    INSERT INTO risk_area(source, original_id, kind, district, name, base_year, min_lat, min_lng, max_lat, max_lng, polygon_json)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    ON DUPLICATE KEY UPDATE kind=VALUES(kind), district=VALUES(district), name=VALUES(name), base_year=VALUES(base_year),
                      min_lat=VALUES(min_lat), min_lng=VALUES(min_lng), max_lat=VALUES(max_lat), max_lng=VALUES(max_lng), polygon_json=VALUES(polygon_json)
                    """, riskSource, a.path("originalId").asText(), a.path("kind").asText(), a.path("district").asText(),
                    a.path("name").asText(""), a.path("baseYear").isInt() ? a.path("baseYear").asInt() : null,
                    minLat, minLng, maxLat, maxLng, mapper.writeValueAsString(a.path("polygon")));
            areas++;
        }

        for (JsonNode c : root.path("coverage")) {
            JsonNode src = sources.get(c.path("kind").asText());
            jdbc.update("""
                    INSERT INTO data_coverage(district, kind, rows_loaded, status, source, source_downloaded) VALUES (?,?,?,?,?,?)
                    ON DUPLICATE KEY UPDATE rows_loaded=VALUES(rows_loaded), status=VALUES(status), source=VALUES(source), source_downloaded=VALUES(source_downloaded)
                    """, c.path("district").asText(), c.path("kind").asText(), c.path("rows").asInt(), c.path("status").asText(),
                    src == null ? "" : src.path("source").asText(), src == null ? null : date(src.path("downloaded").asText(null)));
        }
        return new Result(facilities, areas);
    }

    private int flushFacilities(List<Object[]> rows) {
        if (rows.isEmpty()) return 0;
        jdbc.batchUpdate("""
                INSERT INTO facility(source, original_id, kind, district, lat, lng, source_downloaded, source_reference, name, signal_status, base_date)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE kind=VALUES(kind), district=VALUES(district), lat=VALUES(lat), lng=VALUES(lng),
                  source_downloaded=VALUES(source_downloaded), source_reference=VALUES(source_reference), name=VALUES(name),
                  signal_status=VALUES(signal_status), base_date=VALUES(base_date)
                """, rows);
        return rows.size();
    }

    private static Date date(String iso) {
        if (iso == null || iso.isBlank() || "null".equals(iso)) return null;
        return Date.valueOf(LocalDate.parse(iso.substring(0, 10)));
    }

    private static String textOrNull(JsonNode n) {
        return n == null || n.isNull() || n.asText().isBlank() ? null : n.asText();
    }
}
