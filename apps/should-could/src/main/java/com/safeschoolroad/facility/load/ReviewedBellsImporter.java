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
 * 구청별 안전비상벨 파일(검토 통과분) 적재 (2026-10-10). scripts/prepare_extra_facilities.py 가 만든 후보 파일과
 * docs/extra-bells-import-review.json 의 해시·승인이 맞을 때만 넣는다. 외부 API 호출 없음.
 *
 *   ./gradlew bootRun --args="--import-reviewed-bells
 *       '--bells-file=무제 폴더/품질검토/2026-10-10-추가수급/approved-extra-bells.json'
 *       --bells-review-file=docs/extra-bells-import-review.json"
 *
 * 전국 표준데이터(BELL_STD) 비상벨은 그대로 두고, 그 15m 안에 있는 구청 파일 비상벨은 같은 벨로 보고 넣지 않는다
 * (종로구는 표준데이터 89개 중 69개가 구청 파일에도 있었다). 같은 구의 이전 구청 파일 행(BELL_DISTRICT)만 바꾼다.
 */
@Component
public class ReviewedBellsImporter implements ApplicationRunner {

    public static final String OPTION = "import-reviewed-bells";
    static final String SOURCE = "BELL_DISTRICT";
    // 고정 시설이고 점검 기록이 있어 4년까지 받는다 (prepare_extra_facilities.py 와 같게)
    static final int MAX_AGE_DAYS = 1460;
    static final double SAME_BELL_M = 15;

    private final FacilityRepository facilities;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ConfigurableApplicationContext context;
    private final ObjectMapper json = new ObjectMapper();

    @Value("${PUBLIC_DATA_BACKUP_DIR:무제 폴더/품질검토/db-backups}")
    private String backupDirectory;

    public ReviewedBellsImporter(FacilityRepository facilities, JdbcTemplate jdbc, TransactionTemplate transaction,
                                 ConfigurableApplicationContext context) {
        this.facilities = facilities;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return;
        }
        int code = 0;
        try {
            Path file = Path.of(option(args, "bells-file"));
            JsonNode review = json.readTree(Path.of(option(args, "bells-review-file")).toFile());
            if (Files.size(file) > 20_000_000) throw new IllegalArgumentException("검토 파일이 너무 큽니다.");
            byte[] bytes = Files.readAllBytes(file);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!review.path("approved").asBoolean() || !hash.equals(review.path("candidateSha256").asText())) {
                throw new IllegalArgumentException("검토 승인 파일과 해시가 다릅니다. 검토를 다시 해 주세요.");
            }
            List<FacilityRow> rows = validate(json.readTree(bytes), LocalDate.now());
            Set<String> districts = new HashSet<>();
            rows.forEach(r -> districts.add(r.district()));
            Path backup = Path.of(backupDirectory, "bells-" + UUID.randomUUID() + ".json");
            Files.createDirectories(backup.getParent());
            int[] skipped = {0};
            transaction.executeWithoutResult(status -> {
                // 비상벨 행만 백업한다 (사용자·경로 정보는 읽지 않음)
                var old = jdbc.queryForList("SELECT kind,name,lat,lng,district,source,source_id,CAST(base_date AS CHAR) AS base_date "
                        + "FROM facility WHERE kind = 'EMERGENCY_BELL'");
                try {
                    json.writerWithDefaultPrettyPrinter().writeValue(backup.toFile(), Map.of("facilities", old));
                    Files.writeString(Path.of(backup + ".sha256"), HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(backup))));
                } catch (Exception e) {
                    throw new IllegalStateException("백업 실패. DB를 바꾸지 않습니다.");
                }
                for (String d : districts) {
                    facilities.deleteSourceRegion(SOURCE, d);
                }
                List<double[]> existing = new ArrayList<>();
                for (String d : districts) {
                    jdbc.query("SELECT lat, lng FROM facility WHERE kind = 'EMERGENCY_BELL' AND district = ?",
                            rs -> { existing.add(new double[]{rs.getDouble(1), rs.getDouble(2)}); }, d);
                }
                List<FacilityRow> fresh = withoutSameBells(rows, existing);
                skipped[0] = rows.size() - fresh.size();
                facilities.insertAll(fresh);
            });
            System.out.println("구청 비상벨 적재 완료: " + (rows.size() - skipped[0]) + "개, " + districts.size() + "개 구"
                    + " (표준데이터와 " + SAME_BELL_M + "m 안에서 겹친 " + skipped[0] + "개는 같은 벨로 보고 뺌). 백업: " + backup);
        } catch (Exception e) {
            code = 1;
            System.err.println("구청 비상벨 적재 실패: " + e.getMessage());
        }
        int exit = code;
        System.exit(SpringApplication.exit(context, () -> exit));
    }

    /** 이미 있는 비상벨과 15m 안이면 같은 벨로 본다 */
    static List<FacilityRow> withoutSameBells(List<FacilityRow> rows, List<double[]> existing) {
        List<FacilityRow> out = new ArrayList<>();
        for (FacilityRow r : rows) {
            boolean same = existing.stream().anyMatch(e -> meters(e[0], e[1], r.lat(), r.lng()) <= SAME_BELL_M);
            if (!same) out.add(r);
        }
        return out;
    }

    static double meters(double lat1, double lng1, double lat2, double lng2) {
        return Math.hypot((lat1 - lat2) * 110_540, (lng1 - lng2) * 88_200);
    }

    /** 후보 파일의 모양·범위를 다시 확인한다. 하나라도 틀리면 아무것도 넣지 않는다 */
    static List<FacilityRow> validate(JsonNode root, LocalDate today) {
        if (root.path("version").asInt() != 1 || !root.path("facilities").isArray() || !root.path("districts").isArray()) {
            throw new IllegalArgumentException("후보 파일 형식이 다릅니다.");
        }
        Set<String> declared = new HashSet<>();
        root.path("districts").forEach(d -> declared.add(d.asText()));
        List<FacilityRow> rows = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode r : root.path("facilities")) {
            String id = r.path("sourceId").asText(), district = r.path("district").asText(), name = r.path("name").asText();
            if (!FacilityKind.EMERGENCY_BELL.name().equals(r.path("kind").asText()) || !SOURCE.equals(r.path("source").asText())
                    || id.isBlank() || id.length() > 40 || !ids.add(id)
                    || !FacilityRegion.isValid(district) || FacilityRegion.ALL_SEOUL.equals(district) || !declared.contains(district)
                    || name.isBlank() || name.length() > 100) {
                throw new IllegalArgumentException("비상벨 행의 종류·식별자·구 이름이 맞지 않습니다: " + id);
            }
            double lat = r.path("lat").asDouble(Double.NaN), lng = r.path("lng").asDouble(Double.NaN);
            if (!(lat >= 37.4 && lat <= 37.73 && lng >= 126.75 && lng <= 127.2)) {
                throw new IllegalArgumentException("서울 좌표 범위 밖: " + id);
            }
            LocalDate date = LocalDate.parse(r.path("baseDate").asText());
            if (date.isAfter(today) || date.isBefore(today.minusDays(MAX_AGE_DAYS))) {
                throw new IllegalArgumentException("기준일이 미래이거나 4년이 넘었습니다: " + id);
            }
            rows.add(new FacilityRow(FacilityKind.EMERGENCY_BELL, name, lat, lng, null, district, SOURCE, id, date));
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("적재할 비상벨이 없습니다.");
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
