package com.safeschoolroad.facility.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.facility.*;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** 파일 품질 검토를 마친 자료만 명시적으로 적재. 외부 API 호출 없음. */
@Component
public class ReviewedDataImporter implements ApplicationRunner {
    private final FacilityRepository facilities;
    private final RiskAreaRepository risks;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ConfigurableApplicationContext context;
    @org.springframework.beans.factory.annotation.Value("${PUBLIC_DATA_BACKUP_DIR:무제 폴더/품질검토/db-backups}")
    private String backupDirectory;
    private final ObjectMapper json = new ObjectMapper();
    private static final Map<String,FacilityKind> SOURCES = Map.of(
            "SEOUL_CROSSWALK",FacilityKind.CROSSWALK,"SCHOOL_STD",FacilityKind.SCHOOL,
            "SEOUL_PED_SIGNAL",FacilityKind.PEDESTRIAN_SIGNAL,"SECURITY_LIGHT_STD",FacilityKind.SECURITY_LIGHT);
    private static final Set<String> RISK_KINDS = Set.of("CHILD_PEDESTRIAN_ACCIDENT","CHILD_ACCIDENT_SCHOOLZONE","ELDERLY_PEDESTRIAN_ACCIDENT");
    private final SignalReestimator signalReestimator;
    public ReviewedDataImporter(FacilityRepository facilities, RiskAreaRepository risks, JdbcTemplate jdbc,
            TransactionTemplate transaction, ConfigurableApplicationContext context, SignalReestimator signalReestimator) {
        this.facilities=facilities;this.risks=risks;this.jdbc=jdbc;this.transaction=transaction;this.context=context;this.signalReestimator=signalReestimator;
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("import-reviewed-data")) return;
        int code=0;
        try {
            Path file=Path.of(option(args,"reviewed-data-file"));
            String hash=option(args,"reviewed-data-sha256");
            if (Files.size(file)>50_000_000) throw new IllegalArgumentException("검토 파일이 너무 큽니다.");
            byte[] bytes=Files.readAllBytes(file);
            if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(hash))
                throw new IllegalArgumentException("검토한 파일의 SHA-256과 다릅니다.");
            JsonNode review=json.readTree(Path.of(option(args,"reviewed-data-review-file")).toFile());
            if(!review.path("approved").asBoolean() || !hash.equals(review.path("candidateSha256").asText()))
                throw new IllegalArgumentException("갱신 비교 검토가 통과하지 않았거나 검토 파일이 다릅니다.");
            JsonNode root=json.readTree(bytes);
            List<FacilityRow> rows=validate(root);
            Path backup=Path.of(backupDirectory, "reviewed-"+UUID.randomUUID()+".json");
            Files.createDirectories(backup.getParent());
            transaction.executeWithoutResult(status->{
                // 공공 시설 테이블만 백업. 사용자·경로·GPS·API 캐시는 조회하지 않는다.
                var oldFacilities=jdbc.queryForList("SELECT kind,name,lat,lng,signal_status,district,source,source_id,CAST(base_date AS CHAR) AS base_date FROM facility WHERE source IN ('SEOUL_CROSSWALK','SCHOOL_STD','SEOUL_PED_SIGNAL') OR (source='SECURITY_LIGHT_STD' AND district='은평구')");
                var oldRisks=jdbc.queryForList("SELECT kind,name,area_json,district,source,source_id,base_year FROM risk_area WHERE kind IN ('CHILD_PEDESTRIAN_ACCIDENT','CHILD_ACCIDENT_SCHOOLZONE','ELDERLY_PEDESTRIAN_ACCIDENT')");
                try {
                    json.writerWithDefaultPrettyPrinter().writeValue(backup.toFile(),Map.of("facilities",oldFacilities,"risks",oldRisks));
                    Files.writeString(Path.of(backup+".sha256"),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(backup))));
                }
                catch (Exception e) { throw new IllegalStateException("백업 실패. DB를 변경하지 않습니다."); }
                SOURCES.keySet().forEach(source->facilities.deleteSourceRegion(source,source.equals("SECURITY_LIGHT_STD")?"은평구":"서울"));
                facilities.insertAll(rows);
                signalReestimator.refresh();
                RISK_KINDS.forEach(risks::deleteKind);
                for (JsonNode r:root.path("risks")) risks.insert(r.path("kind").asText(),r.path("name").asText(),r.path("geometry").toString(),r.path("district").asText(),"B552061",r.path("sourceId").asText(),2025);
            });
            System.out.println("검토 원본 적재 완료: 시설 "+rows.size()+"행, 사고 "+root.path("risks").size()+"행. 공공자료 백업: "+backup);
        } catch(Exception e) {code=1;System.err.println("검토 원본 적재 실패: "+e.getClass().getSimpleName()+". DB 트랜잭션 및 입력 파일을 확인해 주세요.");}
        int exit=code;System.exit(SpringApplication.exit(context,()->exit));
    }
    private static String option(ApplicationArguments args,String name) {
        var values=args.getOptionValues(name);
        if(values==null||values.size()!=1||values.get(0).isBlank()) throw new IllegalArgumentException(name+" 필요");
        return values.get(0);
    }
    static List<FacilityRow> validate(JsonNode root) {
        if(root.path("version").asInt()!=1 || !root.path("facilities").isArray() || !root.path("risks").isArray()) throw new IllegalArgumentException("파일 형식 불일치");
        List<FacilityRow> rows=new ArrayList<>();Set<String> ids=new HashSet<>(), sources=new HashSet<>(), kinds=new HashSet<>();
        for(JsonNode r:root.path("facilities")) {
            String source=r.path("source").asText(),id=r.path("sourceId").asText(),district=r.path("district").asText();
            FacilityKind kind=SOURCES.get(source);
            if(kind==null||!kind.name().equals(r.path("kind").asText())||id.isBlank()||id.length()>40||!ids.add(source+":"+id)
                    ||!FacilityRegion.isValid(district)||district.equals("서울")||source.equals("SECURITY_LIGHT_STD")&&!district.equals("은평구")) throw new IllegalArgumentException("시설 식별자/범위 불일치");
            if(!r.path("lat").isNumber()||!r.path("lng").isNumber())throw new IllegalArgumentException("좌표 없음");
            double lat=r.path("lat").asDouble(),lng=r.path("lng").asDouble();validatePoint(lat,lng);
            String date=r.path("baseDate").asText("");String signal=r.path("signalStatus").asText("");
            if(kind==FacilityKind.CROSSWALK&&!Set.of("ESTIMATED","UNKNOWN").contains(signal) || kind!=FacilityKind.CROSSWALK&&!signal.isEmpty())throw new IllegalArgumentException("신호 상태 불일치");
            rows.add(new FacilityRow(kind,r.path("name").asText(),lat,lng,signal.isEmpty()?null:SignalStatus.valueOf(signal),district,source,id,date.isEmpty()?null:LocalDate.parse(date)));
            sources.add(source);
        }
        ids.clear();
        for(JsonNode r:root.path("risks")) {
            String kind=r.path("kind").asText(),id=r.path("sourceId").asText();
            if(!RISK_KINDS.contains(kind)||!r.path("source").asText().equals("B552061")||r.path("baseYear").asInt()!=2025||id.isBlank()||id.length()>40||!ids.add(id))throw new IllegalArgumentException("사고 원본 불일치");
            JsonNode g=r.path("geometry"),polys=g.path("coordinates");
            if(g.path("type").asText().equals("Polygon")) polys=new ObjectMapper().createArrayNode().add(polys);
            else if(!g.path("type").asText().equals("MultiPolygon"))throw new IllegalArgumentException("사고 도형 불일치");
            if(!polys.isArray()||polys.isEmpty())throw new IllegalArgumentException("빈 도형");
            for(JsonNode poly:polys){
                if(!poly.isArray()||poly.isEmpty())throw new IllegalArgumentException("빈 영역");
                for(JsonNode ring:poly){
                    if(!ring.isArray()||ring.size()<4||!ring.get(0).equals(ring.get(ring.size()-1)))throw new IllegalArgumentException("열린 도형");
                    for(JsonNode p:ring){if(!p.isArray()||p.size()<2||!p.get(0).isNumber()||!p.get(1).isNumber())throw new IllegalArgumentException("좌표 오류");validatePoint(p.get(1).asDouble(),p.get(0).asDouble());}
                }
            }
            kinds.add(kind);
        }
        if(!sources.equals(SOURCES.keySet())||!kinds.equals(RISK_KINDS))throw new IllegalArgumentException("갱신 대상 누락. 기존 DB 유지");
        return List.copyOf(rows);
    }
    private static void validatePoint(double lat,double lng){
        if(!Double.isFinite(lat)||!Double.isFinite(lng)||lat<37.4||lat>37.73||lng<126.75||lng>127.2)throw new IllegalArgumentException("서울 좌표 범위 오류");
    }
}
