package com.safeschoolroad.facility.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.external.Http;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 구 이름이 빈 공공 시설의 구를 네이버 Reverse Geocoding(좌표 → 행정구역)으로 채운다 (2026-10-10).
 * 원본 주소에 구가 없거나 구 코드가 빈 시설만 대상이다. 서울 밖이거나 판정이 안 되면 빈칸 그대로 둔다.
 * 시설 좌표만 보낸다(공공 시설 위치라 개인정보가 아님). 사용자 위치·경로는 보내지 않는다.
 *
 *   ./gradlew bootRun --args='--import-fill-districts'
 */
@Component
public class DistrictFillImporter implements ApplicationRunner {

    public static final String OPTION = "import-fill-districts";
    private static final String URL = "https://maps.apigw.ntruss.com/map-reversegeocode/v2/gc?output=json&orders=admcode&coords=";

    private final JdbcTemplate jdbc;
    private final ConfigurableApplicationContext context;
    private final RestClient http = Http.client(Duration.ofSeconds(10));
    private final ObjectMapper json = new ObjectMapper();

    @Value("${NAVER_MAPS_CLIENT_ID:}")
    private String id;

    @Value("${NAVER_MAPS_CLIENT_SECRET:}")
    private String secret;

    public DistrictFillImporter(JdbcTemplate jdbc, ConfigurableApplicationContext context) {
        this.jdbc = jdbc;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return;
        }
        int code = 0;
        try {
            if (id.isBlank() || secret.isBlank()) {
                throw new IllegalStateException(".env 의 NAVER_MAPS_CLIENT_ID / SECRET 이 비어 있습니다.");
            }
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, kind, lat, lng FROM facility WHERE district = ''");
            Map<String, Integer> filled = new TreeMap<>();
            int notSeoul = 0, failed = 0;
            for (Map<String, Object> r : rows) {
                String district = districtOf(((Number) r.get("lat")).doubleValue(), ((Number) r.get("lng")).doubleValue());
                if (district == null) {
                    failed++;
                } else if (district.isEmpty()) {
                    notSeoul++;
                } else if (SeoulDistricts.isDistrict(district)) {
                    jdbc.update("UPDATE facility SET district = ? WHERE id = ? AND district = ''", district, r.get("id"));
                    filled.merge(r.get("kind") + " " + district, 1, Integer::sum);
                } else {
                    failed++;
                }
                Thread.sleep(40); // 네이버 API 에 몰아서 보내지 않게
            }
            System.out.println("구 이름 채우기: 대상 " + rows.size() + "개, 채움 " + filled.values().stream().mapToInt(Integer::intValue).sum()
                    + "개, 서울 밖 " + notSeoul + "개, 판정 실패 " + failed + "개 (빈칸 유지)");
            System.out.println("채운 내역: " + filled);
        } catch (Exception e) {
            code = 1;
            System.err.println("구 이름 채우기 실패: " + e.getMessage());
        }
        int exit = code;
        System.exit(SpringApplication.exit(context, () -> exit));
    }

    /** 서울이면 구 이름, 서울 밖이면 "", 판정 실패면 null */
    String districtOf(double lat, double lng) {
        try {
            String body = http.get().uri(URI.create(URL + String.format(Locale.ROOT, "%.7f,%.7f", lng, lat)))
                    .header("X-NCP-APIGW-API-KEY-ID", id).header("X-NCP-APIGW-API-KEY", secret)
                    .retrieve().body(String.class);
            return parse(json.readTree(body));
        } catch (Exception e) {
            return null;
        }
    }

    static String parse(JsonNode root) {
        JsonNode results = root.path("results");
        if (!"ok".equals(root.path("status").path("name").asText()) || !results.isArray() || results.isEmpty()) {
            return null;
        }
        JsonNode region = results.get(0).path("region");
        if (!"서울특별시".equals(region.path("area1").path("name").asText())) {
            return "";
        }
        String gu = region.path("area2").path("name").asText("");
        return gu.isBlank() ? null : gu;
    }
}
