package com.safeschoolroad.facility;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class DataQualityReport implements ApplicationRunner {
    private final DataQualityAudit audit;
    private final ObjectMapper json;
    private final ConfigurableApplicationContext context;
    public DataQualityReport(DataQualityAudit audit, ObjectMapper json, ConfigurableApplicationContext context) {
        this.audit = audit; this.json = json; this.context = context;
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("import-report-data")) return;
        String report = json.writerWithDefaultPrettyPrinter().writeValueAsString(audit.inspect());
        if (args.containsOption("data-report-output")) {
            var paths = args.getOptionValues("data-report-output");
            if (paths == null || paths.size() != 1 || paths.get(0).isBlank()) throw new IllegalArgumentException("보고서 파일 경로를 하나 입력해 주세요.");
            Files.writeString(Path.of(paths.get(0)), report);
            System.out.println("공공데이터 집계 보고서 생성 완료");
        } else System.out.println(report);
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
