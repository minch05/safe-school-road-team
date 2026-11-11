package com.safeschoolroad.facility.load;

import com.safeschoolroad.facility.FacilityKind;
import com.safeschoolroad.facility.FacilityRegion;
import com.safeschoolroad.facility.FacilityRepository;
import com.safeschoolroad.facility.SignalStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

// 공공데이터 적재 (FR-15). 평소에는 아무것도 하지 않고, 아래처럼 켤 때만 돈다. 다 끝나면 앱이 꺼진다.
//
//   ./gradlew bootRun --args='--import-facilities'                      ← application.yml 의 facility.region (기본 서울 전체)
//   ./gradlew bootRun --args='--import-facilities --facility.region=중랑구' ← 한 구만
//
// 같은 범위를 다시 적재하면 그 범위의 시설을 지우고 새로 넣는다(원본 파일을 새로 받았을 때).
@Component
public class FacilityImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FacilityImporter.class);
    public static final String OPTION = "import-facilities";
    public static final String SUPPLEMENTAL_OPTION = "import-supplemental-facilities";

    private final FacilityRepository repository;
    private final PublicDataBackup backup;
    private final SignalReestimator signals;
    private final TransactionTemplate transaction;
    private final ConfigurableApplicationContext context;

    @Value("${facility.region:서울}")
    private String region;

    @Value("${facility.data-dir:무제 폴더}")
    private String dataDir;

    public FacilityImporter(FacilityRepository repository, TransactionTemplate transaction,
                            ConfigurableApplicationContext context, PublicDataBackup backup, SignalReestimator signals) {
        this.repository = repository;
        this.backup = backup;
        this.signals=signals;
        this.transaction = transaction;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption(OPTION) && !args.containsOption(SUPPLEMENTAL_OPTION)) {
            return;
        }
        int exitCode = 0;
        try {
            if (args.containsOption(OPTION)) importAll(region, Path.of(dataDir));
            if (args.containsOption(SUPPLEMENTAL_OPTION)) importSupplemental(region, Path.of(dataDir));
        } catch (Exception e) {
            log.error("공공데이터 적재 실패: {}", e.getMessage(), e);
            exitCode = 1;
        }
        int code = exitCode;
        System.exit(SpringApplication.exit(context, () -> code));
    }

    public void importAll(String region, Path dir) throws Exception {
        if (!FacilityRegion.isValid(region)) {
            throw new IllegalArgumentException("facility.region 은 '서울' 또는 자치구 이름(예: 중랑구)이어야 합니다: " + region);
        }
        log.info("공공데이터 적재 시작 — 범위: {}, 폴더: {}", region, dir.toAbsolutePath());
        PublicDataFiles files = new PublicDataFiles(dir);

        PedestrianLights pedLights = files.readPedestrianLights();
        log.info("  보행등 {}개 읽음 (서울 전체 좌표 저장 후 현재 규칙으로 재계산)", pedLights.size());
        if(pedLights.size()==0)throw new IllegalStateException("보행등 자료가 0개입니다. 기존 자료를 유지합니다.");

        List<FacilityRow> all = new ArrayList<>();
        all.addAll(report("횡단보도", files.readCrosswalks(pedLights), region));
        all.addAll(report("학교", files.readSchools(), region));
        all.addAll(report("CCTV", files.readCctvs(), region));
        all.addAll(report("안전비상벨", files.readEmergencyBells(), region));
        all.addAll(report("어린이보호구역", dedupeBySourceId(files.readSchoolZones()), region));

        for (FacilityKind kind : List.of(FacilityKind.CROSSWALK,FacilityKind.SCHOOL,FacilityKind.CCTV,FacilityKind.EMERGENCY_BELL,FacilityKind.SCHOOL_ZONE)) {
            if (all.stream().noneMatch(r -> r.kind()==kind)) throw new IllegalStateException("갱신할 "+kind.getLabel()+" 자료가 0개입니다. 기존 자료를 유지합니다.");
        }

        replaceBase(region,all,pedLights.facilityRows());
        logSummary(all);
    }

    void replaceBase(String region,List<FacilityRow> all,List<FacilityRow> pedestrianRows) {
        transaction.executeWithoutResult(status -> {
            backupSources(region,List.of("SEOUL_CROSSWALK", "SCHOOL_STD", "CCTV_STD", "BELL_STD", "SCHOOL_ZONE_STD"));
            backupSources("서울",List.of("SEOUL_PED_SIGNAL"));
            int deleted = repository.deleteSourceRegion("SEOUL_PED_SIGNAL","서울");
            // 추가 자료가 없는 상태에서 기본 자료를 새로 넣어도 지킴이집·보안등은 남긴다.
            for (String source : List.of("SEOUL_CROSSWALK", "SCHOOL_STD", "CCTV_STD", "BELL_STD", "SCHOOL_ZONE_STD")) {
                deleted += repository.deleteSourceRegion(source, region);
            }
            repository.insertAll(all);
            repository.insertAll(pedestrianRows);
            signals.refresh();
            log.info("DB 저장 완료 — 이전 것 {}개 지우고 {}개 넣음", deleted, all.size());
        });
    }

    public void importSupplemental(String region, Path dir) throws Exception {
        if (!FacilityRegion.isValid(region)) throw new IllegalArgumentException("적재 범위를 확인해 주세요.");
        PublicDataFiles files = new PublicDataFiles(dir);
        // 먼저 모든 파일을 끝까지 읽는다. 일부 파일이 잘려 있으면 DB를 전혀 바꾸지 않는다.
        Map<String, List<FacilityRow>> batches = new java.util.LinkedHashMap<>();
        if (java.nio.file.Files.isRegularFile(dir.resolve(PublicDataFiles.SECURITY_LIGHT_FILE))) {
            batches.put("SECURITY_LIGHT_STD", report("보안등", dedupeBySourceId(files.readSecurityLights()), region));
        }
        if (java.nio.file.Files.isRegularFile(dir.resolve(PublicDataFiles.SAFETY_HOUSE_FILE))) {
            batches.put("CHILD_SAFETY_HOUSE", report("아동안전지킴이집", dedupeBySourceId(files.readChildSafetyHouses()), region));
        }
        if (batches.isEmpty()) throw new IllegalStateException("추가 공공데이터 파일이 없습니다.");
        if (batches.values().stream().anyMatch(List::isEmpty)) {
            throw new IllegalStateException("서울 범위에서 읽은 데이터가 0개입니다. 파일 형식·좌표를 확인하세요. 기존 데이터는 유지합니다.");
        }
        transaction.executeWithoutResult(status -> {
            backupSources(region,new ArrayList<>(batches.keySet()));
            batches.forEach((source, rows) -> {
            repository.deleteSourceRegion(source, region);
            repository.insertAll(rows);
            log.info("{} {}개 저장", source, rows.size());
        });
        });
    }

    void backupSources(String region,List<String> sources) {
        Map<String,Object> snapshot=new java.util.LinkedHashMap<>();
        sources.forEach(source->snapshot.put(source,repository.snapshotSourceRegion(source,region)));
        backup.write(Map.of("region",region,"facilities",snapshot));
    }

    // 같은 이름·좌표가 두 줄 있는 경우가 있어 하나만 남긴다
    private static List<FacilityRow> dedupeBySourceId(List<FacilityRow> rows) {
        Map<String, FacilityRow> unique = new java.util.LinkedHashMap<>();
        rows.forEach(r -> unique.putIfAbsent(r.sourceId(), r));
        return new ArrayList<>(unique.values());
    }

    // 범위에 맞는 것만 남기고, 구별 개수와 구를 못 찾은 개수를 남긴다
    private List<FacilityRow> report(String label, List<FacilityRow> rows, String region) {
        long noDistrict = rows.stream().filter(r -> r.district().isEmpty()).count();
        List<FacilityRow> kept = rows.stream().filter(r -> FacilityRegion.includes(region, r.district())).toList();
        log.info("  {}: 서울 {}개 중 {}개 (주소에서 구를 못 찾은 것 {}개{})", label, rows.size(), kept.size(), noDistrict,
                FacilityRegion.ALL_SEOUL.equals(region) ? "는 구 이름 없이 넣음" : "는 뺌");
        return kept;
    }

    private void logSummary(List<FacilityRow> rows) {
        Map<String, Long> byDistrict = rows.stream()
                .collect(Collectors.groupingBy(r -> r.district().isEmpty() ? "(구 없음)" : r.district(),
                        TreeMap::new, Collectors.counting()));
        log.info("구별 시설 수: {}", byDistrict);
        long estimated = rows.stream().filter(r -> r.kind() == FacilityKind.CROSSWALK
                && r.signalStatus() == SignalStatus.ESTIMATED).count();
        long crosswalks = rows.stream().filter(r -> r.kind() == FacilityKind.CROSSWALK).count();
        log.info("횡단보도 신호: 있음 추정 {} / 미확인 {}", estimated, crosswalks - estimated);
    }
}
