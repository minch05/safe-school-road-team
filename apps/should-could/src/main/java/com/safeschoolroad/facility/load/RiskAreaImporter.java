package com.safeschoolroad.facility.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.facility.RiskAreaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import com.safeschoolroad.external.Http;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.Map;

// 사고다발지역(영역) 적재. 공공데이터포털(한국도로교통공단 B552061) API에서 서울 25개 구를 받는다.
//
//   ./gradlew bootRun --args='--import-risk-areas'
//
// 지금 키로 되는 것: 어린이보호구역 내 어린이 사고다발지역, 노인 보행자 사고다발지역 (2026-10-09 확인).
// 보행어린이 사고다발지역도 2026-10-09 승인 확인. 설정으로 포함 여부를 조절한다.
@Component
public class RiskAreaImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RiskAreaImporter.class);
    public static final String OPTION = "import-risk-areas";

    // 종류 → API 경로
    static final Map<String, String> SERVICES = Map.of(
            "CHILD_ACCIDENT_SCHOOLZONE", "schoolzoneChild/getRestSchoolzoneChild",
            "ELDERLY_PEDESTRIAN_ACCIDENT", "frequentzoneOldman/getRestFrequentzoneOldman");

    // 사고다발지역 API 의 구 코드 (행정표준 코드 끝 3자리)
    private static final Map<String, String> GU_CODES = Map.ofEntries(
            Map.entry("110", "종로구"), Map.entry("140", "중구"), Map.entry("170", "용산구"),
            Map.entry("200", "성동구"), Map.entry("215", "광진구"), Map.entry("230", "동대문구"),
            Map.entry("260", "중랑구"), Map.entry("290", "성북구"), Map.entry("305", "강북구"),
            Map.entry("320", "도봉구"), Map.entry("350", "노원구"), Map.entry("380", "은평구"),
            Map.entry("410", "서대문구"), Map.entry("440", "마포구"), Map.entry("470", "양천구"),
            Map.entry("500", "강서구"), Map.entry("530", "구로구"), Map.entry("545", "금천구"),
            Map.entry("560", "영등포구"), Map.entry("590", "동작구"), Map.entry("620", "관악구"),
            Map.entry("650", "서초구"), Map.entry("680", "강남구"), Map.entry("710", "송파구"),
            Map.entry("740", "강동구"));

    private final RiskAreaRepository repository;
    private final TransactionTemplate transaction;
    private final PublicDataBackup backup;
    private final ConfigurableApplicationContext context;
    private final RestClient http = Http.client(Duration.ofSeconds(30));
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${DATA_GO_KR_SERVICE_KEY:}")
    private String serviceKey;

    // 사고다발지역 기준 연도. 최신 연도는 제공기관 응답으로 확인한다. 2026-10-10 파일 점검: 2025년 서울 3종 정상 응답.
    @Value("${risk.include-child-pedestrian:false}")
    private boolean includeChildPedestrian;

    @Value("${risk.accident-year:2025}")
    private int year;

    public RiskAreaImporter(RiskAreaRepository repository, TransactionTemplate transaction,
                            ConfigurableApplicationContext context, PublicDataBackup backup) {
        this.repository = repository;
        this.backup=backup;
        this.transaction = transaction;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return;
        }
        int exitCode = 0;
        try {
            importAll();
        } catch (Exception e) {
            log.error("사고다발지역 적재 실패: {}", e.getMessage(), e);
            exitCode = 1;
        }
        int code = exitCode;
        System.exit(SpringApplication.exit(context, () -> code));
    }

    public void importAll() {
        if (serviceKey.isBlank()) {
            throw new IllegalStateException(".env 의 DATA_GO_KR_SERVICE_KEY 가 비어 있습니다.");
        }
        Map<String, String> services = new java.util.LinkedHashMap<>(SERVICES);
        if (includeChildPedestrian) services.put("CHILD_PEDESTRIAN_ACCIDENT", "frequentzoneChild/getRestFrequentzoneChild");
        Map<String,List<Object[]>> batches = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> service : services.entrySet()) {
            String kind = service.getKey();
            List<Object[]> rows = new ArrayList<>();
            for (Map.Entry<String, String> gu : GU_CODES.entrySet()) {
                rows.addAll(fetch(service.getValue(), gu.getKey(), gu.getValue()));
            }
            if (rows.isEmpty()) throw new IllegalStateException("사고 자료가 0개입니다. 기존 자료를 유지합니다.");
            batches.put(kind,rows);
        }
        transaction.executeWithoutResult(status -> {
            Map<String,Object> snapshot=new java.util.LinkedHashMap<>();
            batches.keySet().forEach(kind->snapshot.put(kind,repository.snapshotKind(kind)));
            backup.write(Map.of("risks",snapshot));
            for (var batch : batches.entrySet()) {
                String kind=batch.getKey();List<Object[]> rows=batch.getValue();
                int deleted = repository.deleteKind(kind);
                for (Object[] r : rows) {
                    repository.insert(kind, (String) r[0], (String) r[1], (String) r[2], "B552061", (String) r[3], year);
                }
                log.info("{} — 이전 것 {}개 지우고 {}개 넣음 ({}년 기준)", kind, deleted, rows.size(), year);
            }
        });
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // {이름, GeoJSON, 구, 원본 번호}
    List<Object[]> fetch(String path, String guCode, String guName) {
        List<Object[]> out = new ArrayList<>();
        for (int page = 1; page <= 20; page++) {
            // 인증키에 + / = 가 들어 있어 직접 인코딩한다 (UriComponentsBuilder 는 + 를 그대로 둔다)
            URI uri = URI.create("https://apis.data.go.kr/B552061/" + path + "?ServiceKey=" + enc(serviceKey)
                    + "&searchYearCd=" + year + "&siDo=11&guGun=" + guCode + "&type=json&numOfRows=100&pageNo=" + page);
            JsonNode root;
            try {
                root = objectMapper.readTree(http.get().uri(uri).retrieve().body(String.class));
            } catch (Exception e) {
                // 외부 예외 URL에는 인증키가 포함될 수 있어 원문과 원인을 로그에 남기지 않는다.
                throw new IllegalStateException(guName + " 사고다발지역을 받지 못했습니다. 제공기관 응답을 확인해 주세요.");
            }
            String code = root.path("resultCode").asText();
            if ("03".equals(code)) {
                break; // 데이터 없음
            }
            if (!"00".equals(code)) {
                throw new IllegalStateException(guName + " 사고다발지역 API 응답이 정상이 아닙니다. 승인과 한도를 확인해 주세요.");
            }
            JsonNode items = root.path("items").path("item");
            if (!items.isArray()) throw new IllegalStateException("사고 자료 형식을 확인할 수 없습니다. 기존 자료를 유지합니다.");
            for (JsonNode it : items) {
                String geom = it.path("geom_json").asText("");
                if (geom.isBlank()) {
                    throw new IllegalStateException("사고 영역 좌표가 누락됐습니다. 기존 자료를 유지합니다.");
                }
                out.add(new Object[]{it.path("spot_nm").asText(""), geom, guName, it.path("afos_fid").asText()});
            }
            if (items.size() < 100) {
                break;
            }
            if (page==20) throw new IllegalStateException("사고 자료 페이지 한도를 넘어 전체를 받지 못했습니다.");
        }
        return out;
    }
}
