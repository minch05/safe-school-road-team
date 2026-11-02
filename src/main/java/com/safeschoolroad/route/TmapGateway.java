package com.safeschoolroad.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** TMAP walking response is used only in memory until the next KST day. */
@Component
public class TmapGateway {
    private static final String URL = "https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1&format=json";
    private final RestClient http = RestClient.builder().requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
        setConnectTimeout(Duration.ofSeconds(5)); setReadTimeout(Duration.ofSeconds(15));
    }}).build();
    private final ObjectMapper mapper;
    private final String appKey;
    private final int dailyLimit;
    private final DailyApiCache cache;

    @org.springframework.beans.factory.annotation.Autowired
    public TmapGateway(ObjectMapper mapper, DailyApiCache cache, @Value("${app.tmap.app-key:}") String appKey,
            @Value("${app.tmap.daily-limit:950}") int dailyLimit) {
        this.mapper = mapper; this.cache = cache; this.appKey = appKey; this.dailyLimit = dailyLimit;
    }
    // Parser and no-key contract tests do not need a database.
    TmapGateway(ObjectMapper mapper, String appKey, int dailyLimit) {
        this(mapper, null, appKey, dailyLimit);
    }

    public boolean configured() { return !appKey.isBlank(); }

    public RouteOption walking(Point start, Point end, int option) {
        if (!List.of(0, 4, 10).contains(option)) throw new IllegalArgumentException("탐색 옵션이 올바르지 않습니다.");
        if (!configured()) throw new ProviderUnavailable("TMAP 키가 설정되지 않았습니다.");
        String key = String.format(Locale.ROOT, "%.6f,%.6f|%.6f,%.6f|%d", start.lat(), start.lng(), end.lat(), end.lng(), option);
        String cached = cache.getToday(key).orElse(null);
        if (cached != null) return parse(cached, option);
        if (!cache.reserveCall(dailyLimit)) throw new ProviderUnavailable("오늘 경로 API 호출 한도에 도달했습니다.");
        Map<String, String> request = Map.of("startX", num(start.lng()), "startY", num(start.lat()),
                "endX", num(end.lng()), "endY", num(end.lat()), "startName", "출발", "endName", "도착",
                "searchOption", Integer.toString(option), "reqCoordType", "WGS84GEO", "resCoordType", "WGS84GEO");
        try {
            String body = http.post().uri(URL).header("appKey", appKey).contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON).body(request).retrieve().body(String.class);
            RouteOption parsed = parse(body, option);
            cache.putToday(key, body);
            return parsed;
        } catch (Exception ex) {
            throw new ProviderUnavailable("경로 API 응답을 확인하지 못했습니다.");
        }
    }

    static String num(double x) { return String.format(Locale.ROOT, "%.6f", x); }

    public RouteOption parse(String body, int option) {
        try {
            JsonNode root = mapper.readTree(body);
            List<Point> line = new ArrayList<>();
            int distance = 0, duration = 0;
            for (JsonNode feature : root.path("features")) {
                JsonNode geometry = feature.path("geometry"), properties = feature.path("properties");
                if ("LineString".equals(geometry.path("type").asText())) {
                    for (JsonNode c : geometry.path("coordinates")) {
                        if (!c.isArray() || c.size() < 2) throw new IllegalArgumentException("좌표 형식 오류");
                        Point point = new Point(c.get(1).asDouble(Double.NaN), c.get(0).asDouble(Double.NaN));
                        if (!point.inSupportedArea()) throw new IllegalArgumentException("범위 밖 좌표");
                        if (line.isEmpty() || !line.get(line.size()-1).equals(point)) line.add(point);
                    }
                }
                if (properties.has("totalDistance")) {
                    distance = properties.path("totalDistance").asInt();
                    duration = properties.path("totalTime").asInt();
                }
            }
            if (line.size() < 2 || distance <= 0 || duration <= 0) throw new IllegalArgumentException("불완전한 경로 응답");
            return new RouteOption(option, line, distance, duration);
        } catch (Exception ex) { throw new ProviderUnavailable("경로 API 응답 형식이 올바르지 않습니다."); }
    }

    public record RouteOption(int option, List<Point> line, int distanceM, int durationSec) { }
    public static class ProviderUnavailable extends RuntimeException {
        public ProviderUnavailable(String message) { super(message); }
    }
}
