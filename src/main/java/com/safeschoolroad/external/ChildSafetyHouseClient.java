package com.safeschoolroad.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.facility.FacilityKind;
import com.safeschoolroad.facility.load.FacilityRow;
import com.safeschoolroad.facility.load.SeoulDistricts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;

// 공식 생활안전지도 IF_0055. 업체명·전화번호·주소는 저장하지 않는다.
@Component
public class ChildSafetyHouseClient {
    private final ObjectMapper mapper;
    private final String key;
    private final RestClient http = Http.client(Duration.ofSeconds(30));
    public ChildSafetyHouseClient(ObjectMapper mapper, @Value("${SAFEMAP_API_KEY:}") String key) {
        this.mapper = mapper; this.key = key;
    }

    public List<FacilityRow> fetchSeoul() {
        if (key.isBlank()) throw new IllegalStateException("생활안전지도 키가 필요합니다.");
        var rows = new LinkedHashMap<String, FacilityRow>();
        int read = 0, total = -1;
        for (int page = 1; page <= 200; page++) {
            JsonNode root;
            try {
                URI uri = URI.create("https://www.safemap.go.kr/openapi2/IF_0055?serviceKey="
                        + URLEncoder.encode(key, StandardCharsets.UTF_8) + "&numOfRows=1000&pageNo=" + page + "&returnType=json");
                root = mapper.readTree(http.get().uri(uri).retrieve().body(String.class));
            } catch (Exception e) {
                throw new IllegalStateException("지킴이집 API 응답을 받지 못했습니다. 기존 데이터는 유지합니다.");
            }
            if (!"00".equals(root.path("header").path("resultCode").asText())) {
                throw new IllegalStateException("지킴이집 API 승인 상태를 확인하세요. 기존 데이터는 유지합니다.");
            }
            JsonNode body = root.path("body");
            int expected = body.path("totalCount").asInt(-1);
            if (expected <= 0 || expected > 200_000 || (total != -1 && total != expected)) {
                throw new IllegalStateException("지킴이집 API의 전체 개수가 올바르지 않거나 수집 중 바뀌었습니다.");
            }
            total = expected;
            JsonNode items = body.path("items").path("item");
            if (!items.isArray() || items.isEmpty()) throw new IllegalStateException("지킴이집 API 페이지가 비었습니다. 기존 데이터는 유지합니다.");
            for (JsonNode item : items) {
                FacilityRow row = toRow(item);
                if (row != null) rows.put(row.sourceId(), row);
            }
            read += items.size();
            if (read >= total) {
                if (rows.isEmpty()) throw new IllegalStateException("서울 지킴이집을 찾지 못했습니다. 기존 데이터는 유지합니다.");
                return List.copyOf(rows.values());
            }
        }
        throw new IllegalStateException("지킴이집 API 페이지 한도를 넘었습니다. 기존 데이터는 유지합니다.");
    }

    static FacilityRow toRow(JsonNode item) {
        if (!"504040".equals(item.path("fclty_cd").asText())) return null;
        String road = item.path("rn_adres").asText(""), lot = item.path("adres").asText("");
        if (!(road + lot).contains("서울") && !"11".equals(item.path("ctprvn_cd").asText())) return null;
        // 공식 데이터의 x/y는 Web Mercator(EPSG:3857), 경위도로 변환한다.
        double x = item.path("x").asDouble(Double.NaN), y = item.path("y").asDouble(Double.NaN);
        double lng = Math.toDegrees(x / 6378137.0);
        double lat = Math.toDegrees(2 * Math.atan(Math.exp(y / 6378137.0)) - Math.PI / 2);
        String id = item.path("objt_id").asText("");
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || lat < 37.40 || lat > 37.73
                || lng < 126.75 || lng > 127.20 || id.isBlank() || id.length() > 40) return null;
        // 도로명 정해진 항목만 이름에 사용. 원본 가게 이름은 AI에도 전달하지 않는다.
        var match = java.util.regex.Pattern.compile("([가-힣0-9]+(?:로|길))\\s*\\d").matcher(road);
        String name = match.find() ? match.group(1) : "";
        int year = item.path("data_yr").asInt(0);
        LocalDate date = year >= 2000 && year <= LocalDate.now().getYear() ? LocalDate.of(year, 1, 1) : null;
        return new FacilityRow(FacilityKind.CHILD_SAFETY_HOUSE, name, lat, lng, null,
                SeoulDistricts.fromAddress(road, lot), "CHILD_SAFETY_HOUSE", id, date);
    }
}
