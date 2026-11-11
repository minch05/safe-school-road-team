package com.safeschoolroad.route;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** DB-backed TMAP cache; only a KST calendar day's response may be read. */
@Component
public class DailyApiCache {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    public DailyApiCache(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<String> getToday(String requestKey) {
        List<String> rows = jdbc.queryForList("SELECT body FROM api_cache WHERE cache_key=? AND api='TMAP' AND cache_day=?",
                String.class, hash(requestKey), LocalDate.now(KST));
        return rows.stream().findFirst();
    }

    public void putToday(String requestKey, String body) {
        String key = hash(requestKey);
        int updated = jdbc.update("UPDATE api_cache SET body=?, cache_day=?, created_at=CURRENT_TIMESTAMP WHERE cache_key=?",
                body, LocalDate.now(KST), key);
        if (updated == 0) {
            try { jdbc.update("INSERT INTO api_cache(cache_key,api,body,cache_day) VALUES (?,'TMAP',?,?)",
                    key, body, LocalDate.now(KST)); }
            catch (DuplicateKeyException race) {
                jdbc.update("UPDATE api_cache SET body=?, cache_day=?, created_at=CURRENT_TIMESTAMP WHERE cache_key=?",
                        body, LocalDate.now(KST), key);
            }
        }
    }

    /** Counts attempted external calls, including failures. One application instance at this stage. */
    public synchronized boolean reserveCall(int dailyLimit) {
        LocalDate today = LocalDate.now(KST);
        List<Integer> rows = jdbc.queryForList("SELECT calls FROM api_usage WHERE usage_day=? AND api='TMAP'",
                Integer.class, today);
        int current = rows.isEmpty() ? 0 : rows.get(0);
        if (current >= dailyLimit) return false;
        if (rows.isEmpty()) {
            try { jdbc.update("INSERT INTO api_usage(usage_day,api,calls) VALUES (?,'TMAP',1)", today); }
            catch (DuplicateKeyException race) { jdbc.update("UPDATE api_usage SET calls=calls+1 WHERE usage_day=? AND api='TMAP'", today); }
        } else jdbc.update("UPDATE api_usage SET calls=calls+1 WHERE usage_day=? AND api='TMAP'", today);
        return true;
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void purgeAtStartup() { purgeOld(); }

    @Scheduled(cron="0 0 * * * *", zone="Asia/Seoul")
    public void purgeOld() {
        jdbc.update("DELETE FROM api_cache WHERE cache_day < ?", LocalDate.now(KST));
    }

    private static String hash(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(("TMAP|"+raw).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
