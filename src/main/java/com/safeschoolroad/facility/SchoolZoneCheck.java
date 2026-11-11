package com.safeschoolroad.facility;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 어린이보호구역 대상 시설 위치 교차 검사 (2026-10-10).
// 보호구역 자료의 대상 시설이 초등학교이고, 같은 이름의 학교(학교 표준데이터)가 300m 넘게 떨어져 있으면
// 보호구역 좌표가 잘못 적힌 것으로 본다. 확인 결과 학교 좌표는 VWorld 검색과 맞았고 보호구역 좌표가 어긋났다
// (예: 개원초 3.7km, 송파초 2.8km, 관악초 0.9km). 이런 점은 경로 집계·지도에서 빼고 "위치 의심"으로 센다.
// 원본 행은 지우지 않는다. 같은 이름의 학교가 없으면 판단할 근거가 없으므로 그대로 쓴다.
@Component
public class SchoolZoneCheck {

    static final double MAX_FROM_SCHOOL_M = 300;

    private final JdbcTemplate jdbc;
    static final long CACHE_NANOS = java.time.Duration.ofMinutes(1).toNanos();
    private final java.util.function.LongSupplier now;
    private record Snapshot(Map<String, List<double[]>> schools, long created) {}
    private volatile Snapshot snapshot;

    @org.springframework.beans.factory.annotation.Autowired
    public SchoolZoneCheck(JdbcTemplate jdbc) {
        this(jdbc, System::nanoTime);
    }

    SchoolZoneCheck(JdbcTemplate jdbc, java.util.function.LongSupplier now) {
        this.jdbc = jdbc;
        this.now = now;
    }

    /** "서울개원초등학교", "개원초등학교" → "개원초등학교". 초등학교가 아니면 null */
    static String elementaryKey(String name) {
        if (name == null) {
            return null;
        }
        String n = name.replaceAll("\\s", "").replaceFirst("^서울(특별시)?", "");
        return n.endsWith("초등학교") && n.length() > 4 ? n : null;
    }

    public boolean misplaced(Facility zone) {
        return zone.kind() == FacilityKind.SCHOOL_ZONE && misplaced(zone.name(), zone.lat(), zone.lng(), schools());
    }

    static boolean misplaced(String zoneName, double lat, double lng, Map<String, List<double[]>> schools) {
        String key = elementaryKey(zoneName);
        if (key == null || !schools.containsKey(key)) {
            return false;
        }
        double best = Double.MAX_VALUE;
        for (double[] s : schools.get(key)) {
            best = Math.min(best, Math.hypot((lat - s[0]) * 110540, (lng - s[1]) * 88200));
        }
        return best > MAX_FROM_SCHOOL_M;
    }

    public List<Facility> keepTrusted(List<Facility> zones) {
        if (zones.isEmpty()) return List.of();
        Map<String, List<double[]>> s = schools();
        return zones.stream().filter(z -> z.kind() != FacilityKind.SCHOOL_ZONE || !misplaced(z.name(), z.lat(), z.lng(), s)).toList();
    }

    /** 자료 안내 화면용: 위치가 의심돼 빼고 있는 보호구역 수 */
    public long misplacedCount() {
        Map<String, List<double[]>> s = schools();
        return jdbc.query("SELECT name, lat, lng FROM facility WHERE kind = 'SCHOOL_ZONE'",
                        (rs, i) -> misplaced(rs.getString(1), rs.getDouble(2), rs.getDouble(3), s))
                .stream().filter(Boolean::booleanValue).count();
    }

    /** 공공데이터를 다시 적재한 뒤 부른다 (다음 조회 때 다시 읽음) */
    public synchronized void reload() {
        snapshot = null;
    }

    private Map<String, List<double[]>> schools() {
        Snapshot s = snapshot;
        if (s != null && now.getAsLong() - s.created() < CACHE_NANOS) return s.schools();
        return refreshSchools();
    }

    private synchronized Map<String, List<double[]>> refreshSchools() {
        Snapshot s = snapshot;
        if (s != null && now.getAsLong() - s.created() < CACHE_NANOS) return s.schools();
        Map<String, List<double[]>> m = new HashMap<>();
        jdbc.query("SELECT name, lat, lng FROM facility WHERE kind = 'SCHOOL'", rs -> {
            String key = elementaryKey(rs.getString(1));
            if (key != null) {
                m.computeIfAbsent(key, k -> new ArrayList<>()).add(new double[]{rs.getDouble(2), rs.getDouble(3)});
            }
        });
        // 재조회 실패 시 만료된 좌표로 판정을 계속하지 않는다. 예외는 기존 오류 처리로 전달한다.
        snapshot = new Snapshot(Map.copyOf(m), now.getAsLong());
        return snapshot.schools();
    }
}
