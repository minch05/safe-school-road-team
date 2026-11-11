package com.safeschoolroad.facility.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.facility.FacilityKind;
import com.safeschoolroad.facility.FacilityRegion;
import com.safeschoolroad.facility.FacilityRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 구청별 보안등 파일(검토 통과분) 적재. scripts/prepare_seoul_lights.py 가 만든 후보 파일과
 * docs/seoul-lights-import-review.json 의 해시·승인이 맞을 때만 넣는다. 외부 API 호출 없음.
 *
 *   ./gradlew bootRun --args='--import-reviewed-lights
 *       --lights-file=무제 폴더/품질검토/2026-10-10/seoul-lights/approved-lights.json
 *       --lights-review-file=docs/seoul-lights-import-review.json'
 *
 * 같은 구에 전국 표준데이터(SECURITY_LIGHT_STD)가 이미 있으면 같은 보안등을 두 번 세지 않도록
 * 구청 파일 기준일이 더 최근일 때만 표준데이터 행을 지우고 구청 파일로 바꾼다.
 */
@Component
public class ReviewedLightsImporter implements ApplicationRunner {

    public static final String OPTION = "import-reviewed-lights";
    static final String SMART_OPTION = "import-reviewed-smart-pole-lights";
    static final String SMART_SOURCE = "SMART_POLE_LIGHT";
    static final String SOURCE = "SECURITY_LIGHT_DISTRICT";
    static final String STD_SOURCE = "SECURITY_LIGHT_STD";
    // 앱의 갱신 점검 기준과 같다 (2년 넘은 기준일은 쓰지 않음)
    static final int MAX_AGE_DAYS = 730;

    private final FacilityRepository facilities;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ConfigurableApplicationContext context;
    private final ObjectMapper json = new ObjectMapper();

    @Value("${PUBLIC_DATA_BACKUP_DIR:무제 폴더/품질검토/db-backups}")
    private String backupDirectory;

    public ReviewedLightsImporter(FacilityRepository facilities, JdbcTemplate jdbc, TransactionTemplate transaction,
                                  ConfigurableApplicationContext context) {
        this.facilities = facilities;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION) && !args.containsOption(SMART_OPTION)) {
            return;
        }
        int code = 0;
        try {
            Path file = Path.of(option(args, "lights-file"));
            JsonNode review = json.readTree(Path.of(option(args, "lights-review-file")).toFile());
            if (Files.size(file) > 50_000_000) throw new IllegalArgumentException("검토 파일이 너무 큽니다.");
            byte[] bytes = Files.readAllBytes(file);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!review.path("approved").asBoolean() || !hash.equals(review.path("candidateSha256").asText())) {
                throw new IllegalArgumentException("검토 승인 파일과 해시가 다릅니다. 검토를 다시 해 주세요.");
            }
            boolean smart = args.containsOption(SMART_OPTION);
            if (smart && args.containsOption(OPTION)) throw new IllegalArgumentException("두 적재 모드를 함께 사용할 수 없어요.");
            JsonNode root = json.readTree(bytes);
            if (smart && !"SMART_POLE_ONLY".equals(root.path("scope").asText())) throw new IllegalArgumentException("스마트폴 자료 범위 불일치");
            String source = smart ? SMART_SOURCE : SOURCE;
            List<FacilityRow> rows = validate(root, LocalDate.now(), source);
            Set<String> districts = new HashSet<>();
            rows.forEach(r -> districts.add(r.district()));
            Path backup = Path.of(backupDirectory, "lights-" + UUID.randomUUID() + ".json");
            Files.createDirectories(backup.getParent());
            int[] replacedStd = {0};
            transaction.executeWithoutResult(status -> {
                // 보안등 행만 백업한다 (사용자·경로 정보는 읽지 않음)
                var old = jdbc.queryForList("SELECT kind,name,lat,lng,district,source,source_id,CAST(base_date AS CHAR) AS base_date "
                        + "FROM facility WHERE kind = 'SECURITY_LIGHT'");
                try {
                    json.writerWithDefaultPrettyPrinter().writeValue(backup.toFile(), Map.of("facilities", old));
                    Files.writeString(Path.of(backup + ".sha256"), HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(backup))));
                } catch (Exception e) {
                    throw new IllegalStateException("백업 실패. DB를 바꾸지 않습니다.");
                }
                if (smart) {
                    replaceSmartPoleRows(rows, districts);
                    return; // 부분 스마트폴 자료로 구청 전수 자료를 교체하지 않는다.
                }
                deleteDistrictRows(districts);
                for (String d : districts) {
                    LocalDate stdDate = jdbc.queryForList("SELECT MAX(base_date) FROM facility WHERE source = ? AND district = ?",
                            java.sql.Date.class, STD_SOURCE, d).stream().filter(java.util.Objects::nonNull)
                            .map(java.sql.Date::toLocalDate).findFirst().orElse(null);
                    LocalDate fileDate = rows.stream().filter(r -> r.district().equals(d)).map(FacilityRow::baseDate)
                            .max(LocalDate::compareTo).orElseThrow();
                    if (stdDate != null && fileDate.isAfter(stdDate)) {
                        replacedStd[0] += jdbc.update("DELETE FROM facility WHERE source = ? AND district = ?", STD_SOURCE, d);
                    } else if (stdDate != null) {
                        rows.removeIf(r -> r.district().equals(d)); // 표준데이터가 더 최근이면 그대로 둔다
                    }
                }
                facilities.insertAll(rows);
            });
            System.out.println((smart ? "스마트폴 보안등 일부 적재 완료: " : "구청 보안등 적재 완료: ") + rows.size() + "개, " + districts.size() + "개 구"
                    + " (같은 구의 오래된 표준데이터 " + replacedStd[0] + "개를 대신함). 백업: " + backup);
        } catch (Exception e) {
            code = 1;
            System.err.println("구청 보안등 적재 실패: " + e.getMessage());
        }
        int exit = code;
        System.exit(SpringApplication.exit(context, () -> exit));
    }

    // run의 백업 이후 트랜잭션 안에서만 호출한다.
    void replaceSmartPoleRows(List<FacilityRow> rows, Set<String> districts) {
        for (String d : districts) facilities.deleteSourceRegion(SMART_SOURCE, d);
        facilities.insertAll(rows);
    }

    // 파일에 실제 행이 있는 구만 교체한다. 다른 구의 검토 자료는 유지한다.
    void deleteDistrictRows(Set<String> districts) {
        for (String district : districts) {
            facilities.deleteSourceRegion(SOURCE, district);
        }
    }

    /** 후보 파일의 모양·범위를 다시 확인한다. 하나라도 틀리면 아무것도 넣지 않는다 */
    static List<FacilityRow> validate(JsonNode root, LocalDate today) {
        return validate(root, today, SOURCE);
    }
    static List<FacilityRow> validate(JsonNode root, LocalDate today, String source) {
        if (!Set.of(SOURCE, SMART_SOURCE).contains(source)) throw new IllegalArgumentException("허용되지 않은 원본");
        if (root.path("version").asInt() != 1 || !root.path("facilities").isArray() || !root.path("districts").isArray()) {
            throw new IllegalArgumentException("후보 파일 형식이 다릅니다.");
        }
        // 파일이 스스로 밝힌 구 목록 밖의 행은 받지 않는다 (검토하지 않은 구가 섞이지 않게)
        Set<String> declared = new HashSet<>();
        root.path("districts").forEach(d -> declared.add(d.asText()));
        List<FacilityRow> rows = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode r : root.path("facilities")) {
            String id = r.path("sourceId").asText(), district = r.path("district").asText(), name = r.path("name").asText();
            if (!FacilityKind.SECURITY_LIGHT.name().equals(r.path("kind").asText()) || !source.equals(r.path("source").asText())
                    || id.isBlank() || id.length() > 40 || !ids.add(id)
                    || !FacilityRegion.isValid(district) || FacilityRegion.ALL_SEOUL.equals(district) || !declared.contains(district)
                    || name.isBlank() || name.length() > 100) {
                throw new IllegalArgumentException("보안등 행의 종류·식별자·구 이름이 맞지 않습니다: " + id);
            }
            double lat = r.path("lat").asDouble(Double.NaN), lng = r.path("lng").asDouble(Double.NaN);
            if (!(lat >= 37.4 && lat <= 37.73 && lng >= 126.75 && lng <= 127.2)) {
                throw new IllegalArgumentException("서울 좌표 범위 밖: " + id);
            }
            LocalDate date = LocalDate.parse(r.path("baseDate").asText());
            if (date.isAfter(today) || date.isBefore(today.minusDays(MAX_AGE_DAYS))) {
                throw new IllegalArgumentException("기준일이 미래이거나 2년이 넘었습니다: " + id);
            }
            rows.add(new FacilityRow(FacilityKind.SECURITY_LIGHT, name, lat, lng, null, district, source, id, date));
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("적재할 보안등이 없습니다.");
        }
        return rows;
    }

    private static String option(ApplicationArguments args, String name) {
        var values = args.getOptionValues(name);
        if (values == null || values.size() != 1 || values.get(0).isBlank()) {
            throw new IllegalArgumentException("--" + name + " 이(가) 필요합니다.");
        }
        return values.get(0);
    }
}
