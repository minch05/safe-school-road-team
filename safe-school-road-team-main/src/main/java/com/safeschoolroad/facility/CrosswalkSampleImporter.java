package com.safeschoolroad.facility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Imports only the reviewed 10-record Jungnang development sample on explicit command. */
@Component
public class CrosswalkSampleImporter implements CommandLineRunner {
    static final String SOURCE = "서울시 교통안전시설물 횡단보도 OA-15554";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CrosswalkSampleImporter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override public void run(String... args) throws Exception {
        if (List.of(args).contains("--import-crosswalk-sample")) {
            int count = importSample();
            System.out.println("개발용 횡단보도 표본 " + count + "건 확인; 정식 서울 전체 적재가 아닙니다.");
        }
    }

    @Transactional public int importSample() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/sample/crosswalks-jungnang-10.json")) {
            if (in == null) throw new IllegalStateException("횡단보도 표본 파일이 없습니다.");
            JsonNode root = mapper.readTree(in);
            if (!"중랑구".equals(root.path("district").asText())) throw new IllegalArgumentException("검토되지 않은 구입니다.");
            LocalDate downloaded = LocalDate.parse(root.path("downloaded").asText());
            JsonNode records = root.path("records");
            if (!records.isArray() || records.size() != 10) throw new IllegalArgumentException("표본 건수가 예상과 다릅니다.");
            int imported = 0;
            for (JsonNode row : records) {
                if (!"260".equals(row.path("GU_CD").asText())) throw new IllegalArgumentException("자치구 코드가 다릅니다.");
                String originalId = row.path("CRSWK_MNG_NO1").asText("");
                if (!originalId.matches("[0-9-]{6,40}")) throw new IllegalArgumentException("원본 ID가 없습니다.");
                double x = row.path("XCRD").asDouble(Double.NaN), y = row.path("YCRD").asDouble(Double.NaN);
                if (!Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("원본 좌표가 없습니다.");
                double[] wgs = Tm5186.toWgs84(x, y);
                if (wgs[0] < 37.4 || wgs[0] > 37.75 || wgs[1] < 126.75 || wgs[1] > 127.25)
                    throw new IllegalArgumentException("서울 범위 밖 변환 좌표입니다.");
                int updated = jdbc.update("UPDATE facility SET lat=?, lng=?, source_downloaded=? WHERE source=? AND original_id=?",
                        wgs[0], wgs[1], Date.valueOf(downloaded), SOURCE, originalId);
                if (updated == 0) jdbc.update("INSERT INTO facility(source, original_id, kind, district, lat, lng, source_downloaded, source_reference) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        SOURCE, originalId, "CROSSWALK", "중랑구", wgs[0], wgs[1], Date.valueOf(downloaded),
                        "서울시 OA-15554 / trafficSafetyA004AInfo; 개발용 원본 표본");
                imported++;
            }
            return imported;
        }
    }
}
