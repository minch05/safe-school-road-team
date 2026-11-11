package com.safeschoolroad.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.time.Duration;
import java.util.List;

// VWorld 검색 (FR-02 주소 검색). 장소 이름과 도로명 주소를 함께 찾는다.
// 결과는 화면에 보여 주기만 하고 저장하지 않는다. 저장하는 것은 사용자가 고른 좌표와 사용자가 붙인 이름뿐(HANDOFF 3장).
@Component
public class VworldSearchClient {

    private final RestClient http = Http.client(Duration.ofSeconds(10));
    private final ObjectMapper objectMapper;
    private final NaverSearchClient naver;

    @Value("${VWORLD_API_KEY:}")
    private String key;

    public VworldSearchClient(ObjectMapper objectMapper, NaverSearchClient naver) {
        this.objectMapper = objectMapper;
        this.naver = naver;
    }

    /** title: 장소 이름(장소 검색일 때만), address: 보여 주기용 주소 */
    public record Item(String title, String address, double lat, double lng, boolean isPlace) {
    }

    public List<Item> search(String query) {
        String q = query.strip();
        if (q.length() < 2) {
            return List.of();
        }
        List<Item> primary = naver.search(q);
        if (!primary.isEmpty()) return primary;
        if (key.isBlank()) return List.of();
        // 숫자가 있으면 주소일 가능성이 높아 주소를 먼저 보여 준다
        boolean looksLikeAddress = q.chars().anyMatch(Character::isDigit);
        List<Item> places = request(q, "place", null);
        List<Item> roads = request(q, "address", "road");
        List<Item> out = new ArrayList<>(looksLikeAddress ? roads : places);
        out.addAll(looksLikeAddress ? places : roads);
        return out.stream().limit(10).toList();
    }

    private List<Item> request(String q, String type, String category) {
        String url = "https://api.vworld.kr/req/search?service=search&request=search&version=2.0&crs=EPSG:4326"
                + "&size=7&page=1&format=json&type=" + type + (category == null ? "" : "&category=" + category)
                // 서울만 찾는다 (서비스 지역)
                + "&bbox=126.76,37.41,127.19,37.72"
                + "&query=" + URLEncoder.encode(q, StandardCharsets.UTF_8) + "&key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
        List<Item> items = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(http.get().uri(URI.create(url)).retrieve().body(String.class));
            for (JsonNode it : root.path("response").path("result").path("items")) {
                double lng = it.path("point").path("x").asDouble();
                double lat = it.path("point").path("y").asDouble();
                if ("place".equals(type)) {
                    items.add(new Item(it.path("title").asText(""), it.path("address").path("road").asText(
                            it.path("address").path("parcel").asText("")), lat, lng, true));
                } else {
                    String road = it.path("address").path("road").asText("");
                    String building = it.path("address").path("bldnm").asText("");
                    items.add(new Item(building, road, lat, lng, false));
                }
            }
        } catch (Exception e) {
            // 검색이 안 되면 빈 결과. 사용자는 지도에서 직접 고를 수 있다
        }
        return items;
    }
}
