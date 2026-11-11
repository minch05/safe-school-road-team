package com.safeschoolroad.explore;

import com.safeschoolroad.external.ApiCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

// 비로그인 조회 한도 (HANDOFF 5-7): 기기당 하루 3회, 비로그인 전체 합계 하루 300건
@Component
public class GuestQuota {

    static final String TOTAL_API = "GUEST";

    private final JdbcTemplate jdbc;
    private java.time.Clock clock = java.time.Clock.system(ApiCache.KST);

    @Value("${guest.per-device:3}")
    private int perDevice;

    @Value("${guest.total:300}")
    private int total;

    public GuestQuota(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int perDevice() {
        return perDevice;
    }

    public int used(String device) {
        List<Integer> rows = jdbc.queryForList("SELECT calls FROM guest_quota WHERE day = ? AND device = ?",
                Integer.class, LocalDate.now(clock), device);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    public int left(String device) {
        return Math.max(0, perDevice - used(device));
    }

    /** 한 번 쓸 수 있으면 세고 true. 한도에 닿았으면 false */
    @org.springframework.transaction.annotation.Transactional
    public boolean tryUse(String device) {
        if (perDevice <= 0 || total <= 0) return false;
        LocalDate day = LocalDate.now(clock); // 자정에 두 카운터가 서로 다른 날짜를 쓰지 않게 한 번만 읽는다.
        // 모든 인스턴스가 전체 → 기기 순으로 잠금을 잡는다.
        jdbc.update("INSERT INTO api_usage (day, api, calls) VALUES (?, ?, 0) ON DUPLICATE KEY UPDATE calls = calls", day, TOTAL_API);
        int totalUsed = jdbc.queryForObject("SELECT calls FROM api_usage WHERE day = ? AND api = ? FOR UPDATE", Integer.class, day, TOTAL_API);
        if (totalUsed >= total) return false;
        jdbc.update("INSERT INTO guest_quota (day, device, calls) VALUES (?, ?, 0) ON DUPLICATE KEY UPDATE calls = calls", day, device);
        int deviceUsed = jdbc.queryForObject("SELECT calls FROM guest_quota WHERE day = ? AND device = ? FOR UPDATE", Integer.class, day, device);
        if (deviceUsed >= perDevice) return false;
        jdbc.update("UPDATE guest_quota SET calls = calls + 1 WHERE day = ? AND device = ?", day, device);
        jdbc.update("UPDATE api_usage SET calls = calls + 1 WHERE day = ? AND api = ?", day, TOTAL_API);
        return true;
    }
}
