package com.safeschoolroad.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

// 외부 API 당일 캐시 + 하루 호출 수 (api_cache, api_usage 테이블).
// TMAP 약관: 받은 데이터는 24시간 넘게 쓸 수 없다 → 오늘(한국 시간) 만든 것만 쓰고, 날짜가 바뀌면 지운다.
@Component
public class ApiCache {

    private static final Logger log = LoggerFactory.getLogger(ApiCache.class);
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;

    public ApiCache(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> getToday(String api, String requestKey) {
        List<String> rows = jdbc.queryForList(
                "SELECT body FROM api_cache WHERE cache_key = ? AND created_at >= ?", String.class,
                hash(api, requestKey), Timestamp.valueOf(todayStart()));
        return rows.stream().findFirst();
    }

    public void put(String api, String requestKey, String body) {
        String key = hash(api, requestKey);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(KST));
        try {
            jdbc.update("INSERT INTO api_cache (cache_key, api, body, created_at) VALUES (?, ?, ?, ?)", key, api, body, now);
        } catch (DuplicateKeyException e) {
            // 어제 것이 아직 안 지워졌거나 동시에 같은 요청이 왔다
            jdbc.update("UPDATE api_cache SET body = ?, created_at = ? WHERE cache_key = ?", body, now, key);
        }
    }

    /** 오늘 이 API를 몇 번 불렀는지 */
    public int callsToday(String api) {
        List<Integer> rows = jdbc.queryForList("SELECT calls FROM api_usage WHERE day = ? AND api = ?", Integer.class,
                LocalDate.now(KST), api);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    public void countCall(String api) {
        jdbc.update("INSERT INTO api_usage (day, api, calls) VALUES (?, ?, 1) ON DUPLICATE KEY UPDATE calls = calls + 1",
                LocalDate.now(KST), api);
    }

    // 매시 정각: 오늘 전에 만든 캐시를 지운다 (TMAP 24시간 제한)
    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Seoul")
    public void purgeOld() {
        int n = jdbc.update("DELETE FROM api_cache WHERE created_at < ?", Timestamp.valueOf(todayStart()));
        if (n > 0) {
            log.info("어제까지의 외부 API 캐시 {}건을 지웠습니다", n);
        }
    }

    private static LocalDateTime todayStart() {
        return LocalDate.now(KST).atStartOfDay();
    }

    private static String hash(String api, String requestKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((api + "|" + requestKey).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
