package com.safeschoolroad.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.route.Point;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
public class SearchApi {
    private final RestClient http = RestClient.builder().requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
        setConnectTimeout(Duration.ofSeconds(4)); setReadTimeout(Duration.ofSeconds(6));
    }}).build();
    private final ObjectMapper mapper;
    private final String mapId, mapSecret, searchId, searchSecret, vworldKey;
    public SearchApi(ObjectMapper mapper, @Value("${app.search.naver-client-id:}") String mapId,
            @Value("${app.search.naver-client-secret:}") String mapSecret,
            @Value("${app.search.naver-search-client-id:}") String searchId,
            @Value("${app.search.naver-search-client-secret:}") String searchSecret,
            @Value("${app.search.vworld-key:}") String vworldKey) {
        this.mapper = mapper; this.mapId = mapId; this.mapSecret = mapSecret;
        this.searchId = searchId; this.searchSecret = searchSecret; this.vworldKey = vworldKey;
    }
    public record Item(String name, String address, double lat, double lng, String provider) { }

    @GetMapping("/api/map-config") Map<String,Object> config(@Value("${app.map.naver-client-id:}") String naverId) {
        return Map.of("naverClientId", naverId, "vworldKey", vworldKey,
                "naverDefault", true, "vworldSecondary", true);
    }

    @GetMapping("/api/places/search") ResponseEntity<?> search(@RequestParam String q) {
        String query = q.strip();
        if (query.length() < 2 || query.length() > 80) return ResponseEntity.badRequest().body(Map.of("error", "검색어는 2~80자로 입력해 주세요."));
        List<Item> found = new ArrayList<>();
        if (!mapId.isBlank() && !mapSecret.isBlank()) found.addAll(naverAddress(query));
        if (found.isEmpty() && !searchId.isBlank() && !searchSecret.isBlank()) found.addAll(naverLocal(query));
        if (found.isEmpty() && !vworldKey.isBlank()) found.addAll(vworld(query));
        if (found.isEmpty() && mapId.isBlank() && searchId.isBlank() && vworldKey.isBlank())
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "검색 API 키가 없어 지도에서 직접 고르거나 좌표를 입력해 주세요."));
        return ResponseEntity.ok(found.stream().limit(10).toList());
    }

    private List<Item> naverAddress(String q) {
        String url = "https://maps.apigw.ntruss.com/map-geocode/v2/geocode?count=5&query=" + encode(q);
        try {
            JsonNode root = mapper.readTree(http.get().uri(URI.create(url))
                    .header("X-NCP-APIGW-API-KEY-ID", mapId).header("X-NCP-APIGW-API-KEY", mapSecret)
                    .retrieve().body(String.class));
            List<Item> items = new ArrayList<>();
            for (JsonNode node : root.path("addresses")) {
                double lat = node.path("y").asDouble(Double.NaN), lng = node.path("x").asDouble(Double.NaN);
                if (!new Point(lat, lng).inSupportedArea()) continue;
                String address = node.path("roadAddress").asText("");
                if (address.isBlank()) address = node.path("jibunAddress").asText("");
                items.add(new Item(address, address, lat, lng, "Naver"));
            }
            return items;
        } catch (Exception ignored) { return List.of(); }
    }

    private List<Item> naverLocal(String q) {
        String url = "https://naverapihub.apigw.ntruss.com/search/v1/local?display=5&query=" + encode(q);
        try {
            JsonNode root = mapper.readTree(http.get().uri(URI.create(url))
                    .header("X-NCP-APIGW-API-KEY-ID", searchId).header("X-NCP-APIGW-API-KEY", searchSecret)
                    .retrieve().body(String.class));
            List<Item> items = new ArrayList<>();
            for (JsonNode node : root.path("items")) {
                double lat = coordinate(node.path("mapy").asText("")), lng = coordinate(node.path("mapx").asText(""));
                if (!new Point(lat, lng).inSupportedArea()) continue;
                String name = node.path("title").asText("").replaceAll("(?i)</?b>", "");
                items.add(new Item(name, node.path("roadAddress").asText(""), lat, lng, "Naver"));
            }
            return items;
        } catch (Exception ignored) { return List.of(); }
    }

    private List<Item> vworld(String q) {
        String url = "https://api.vworld.kr/req/search?service=search&request=search&version=2.0&crs=EPSG:4326"
                + "&size=7&page=1&format=json&type=place&bbox=126.76,37.41,127.19,37.72&query=" + encode(q)
                + "&key=" + encode(vworldKey);
        try {
            JsonNode root = mapper.readTree(http.get().uri(URI.create(url)).retrieve().body(String.class));
            List<Item> items = new ArrayList<>();
            for (JsonNode node : root.path("response").path("result").path("items")) {
                double lat = node.path("point").path("y").asDouble(Double.NaN);
                double lng = node.path("point").path("x").asDouble(Double.NaN);
                if (!new Point(lat, lng).inSupportedArea()) continue;
                items.add(new Item(node.path("title").asText(""), node.path("address").path("road").asText(""), lat, lng, "VWorld"));
            }
            return items;
        } catch (Exception ignored) { return List.of(); }
    }
    static double coordinate(String x) { try { double v=Double.parseDouble(x);return Math.abs(v)>180?v/10_000_000d:v; } catch(Exception e){return Double.NaN;} }
    private static String encode(String q) { return URLEncoder.encode(q, StandardCharsets.UTF_8); }
}
