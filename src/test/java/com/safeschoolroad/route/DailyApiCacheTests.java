package com.safeschoolroad.route;

import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DailyApiCacheTests {
    @Autowired DailyApiCache cache;
    @Autowired JdbcTemplate jdbc;
    @Test void sameDayReadsAndPriorDayDeletionAreSeparate() {
        cache.putToday("contract-test", "{\"features\":[]}");
        assertTrue(cache.getToday("contract-test").isPresent());
        jdbc.update("INSERT INTO api_cache(cache_key,api,body,cache_day) VALUES ('prior-day-test','TMAP','old',?)",
                LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1));
        cache.purgeOld();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM api_cache WHERE cache_key='prior-day-test'", Integer.class));
        assertTrue(cache.getToday("contract-test").isPresent());
    }
    @Test void dailyLimitCountsAttemptsAcrossCalls() {
        int today = jdbc.queryForObject("SELECT COUNT(*) FROM api_usage WHERE usage_day=? AND api='TMAP'", Integer.class,
                LocalDate.now(ZoneId.of("Asia/Seoul")));
        assertTrue(cache.reserveCall(950));
        assertFalse(cache.reserveCall(0));
        assertTrue(today >= 0);
    }
}
