package com.safeschoolroad.facility.load;

import com.safeschoolroad.external.ChildSafetyHouseClient;
import com.safeschoolroad.facility.FacilityRegion;
import com.safeschoolroad.facility.FacilityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ChildSafetyHouseImporter implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ChildSafetyHouseImporter.class);
    private final ChildSafetyHouseClient client;
    private final FacilityRepository repository;
    private final TransactionTemplate transaction;
    private final ConfigurableApplicationContext context;
    @Value("${facility.region:서울}") private String region;

    public ChildSafetyHouseImporter(ChildSafetyHouseClient client, FacilityRepository repository,
                                    TransactionTemplate transaction, ConfigurableApplicationContext context) {
        this.client = client; this.repository = repository; this.transaction = transaction; this.context = context;
    }

    @Override public void run(ApplicationArguments args) {
        if (!args.containsOption("import-child-safety-houses")) return;
        int exit = 0;
        try {
            if (!FacilityRegion.isValid(region)) throw new IllegalArgumentException("적재 범위를 확인하세요.");
            var rows = client.fetchSeoul().stream().filter(r -> FacilityRegion.includes(region, r.district())).toList();
            if (rows.isEmpty()) throw new IllegalStateException("선택한 범위의 지킴이집이 0개입니다. 기존 데이터는 유지합니다.");
            transaction.executeWithoutResult(status -> {
                repository.deleteSourceRegion("CHILD_SAFETY_HOUSE", region);
                repository.insertAll(rows);
            });
            log.info("아동안전지킴이집 {}개 적재 완료 ({})", rows.size(), region);
        } catch (Exception e) {
            log.error("아동안전지킴이집 적재 실패: {}", e.getMessage()); exit = 1;
        }
        int code = exit;
        System.exit(SpringApplication.exit(context, () -> code));
    }
}
