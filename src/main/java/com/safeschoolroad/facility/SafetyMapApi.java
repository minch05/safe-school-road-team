package com.safeschoolroad.facility;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.external.SafeMapClient;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/safety-map")
public class SafetyMapApi {
    private static final int FACILITY_LIMIT = 400;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SafeMapClient safeMap;

    public SafetyMapApi(JdbcTemplate jdbc, ObjectMapper json, SafeMapClient safeMap) {
        this.jdbc = jdbc;
        this.json = json;
        this.safeMap = safeMap;
    }

    public record Facility(String kind, String name, double lat, double lng, String source, String sourceDownloaded) { }
    public record Accident(String name, String source, Integer year, Object polygon) { }

    @GetMapping
    Map<String, Object> layers(@RequestParam double south, @RequestParam double west,
                               @RequestParam double north, @RequestParam double east) {
        validate(south, west, north, east);
        List<Facility> facilities = jdbc.query("""
                SELECT kind,name,lat,lng,source,source_downloaded FROM facility
                WHERE lat BETWEEN ? AND ? AND lng BETWEEN ? AND ?
                ORDER BY kind,id LIMIT 401""",
                (rs, i) -> new Facility(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4),
                        rs.getString(5), rs.getString(6)), south, north, west, east);
        List<Accident> accidents = jdbc.query("""
                SELECT name,source,base_year,polygon_json FROM risk_area
                WHERE max_lat>=? AND min_lat<=? AND max_lng>=? AND min_lng<=? ORDER BY id LIMIT 100""",
                (rs, i) -> new Accident(rs.getString(1), rs.getString(2), rs.getObject(3, Integer.class),
                        polygon(rs.getString(4))), south, north, west, east);
        return Map.of("facilities", facilities.stream().limit(FACILITY_LIMIT).toList(), "accidents", accidents,
                "limited", facilities.size() > FACILITY_LIMIT);
    }

    @GetMapping(value="/crime", produces=MediaType.IMAGE_PNG_VALUE)
    ResponseEntity<byte[]> crime(@RequestParam double south, @RequestParam double west,
                                 @RequestParam double north, @RequestParam double east) {
        validate(south, west, north, east);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_PNG).body(safeMap.crimeMap(south, west, north, east));
    }

    private Object polygon(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("저장된 사고 영역 형식이 올바르지 않습니다.", ex); }
    }

    private void validate(double south, double west, double north, double east) {
        if (!Double.isFinite(south) || !Double.isFinite(west) || !Double.isFinite(north) || !Double.isFinite(east)
                || south < 37.40 || north > 37.73 || west < 126.75 || east > 127.20
                || north <= south || east <= west || north - south > .12 || east - west > .12) {
            throw new IllegalArgumentException("서울 안에서 지도를 확대해서 조회해 주세요.");
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler(SafeMapClient.Unavailable.class)
    ResponseEntity<Map<String, String>> unavailable(SafeMapClient.Unavailable error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", error.getMessage()));
    }
}
