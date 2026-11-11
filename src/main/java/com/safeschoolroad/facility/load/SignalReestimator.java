package com.safeschoolroad.facility.load;

import com.safeschoolroad.route.analysis.SignalLinks;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 저장된 횡단보도 신호 상태를 지금 규칙(SignalLinks: 보행등을 15m 안 가장 가까운 횡단보도 하나에만 연결)으로 다시 계산한다.
 * 경로 분석은 이미 이 규칙으로 계산하므로, 지도에 보이는 상태도 같게 맞추는 용도다. 원본 확정값(YES)은 바꾸지 않는다.
 * 공공데이터를 다시 적재한 뒤에도 한 번 돌린다.
 *
 *   ./gradlew bootRun --args='--import-reestimate-signals'
 */
@Component
public class SignalReestimator implements ApplicationRunner {

    public static final String OPTION = "import-reestimate-signals";
    private static final double CELL = 0.0003; // 약 30m 칸 (연결 거리 15m 보다 넉넉하게)

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ConfigurableApplicationContext context;

    public SignalReestimator(JdbcTemplate jdbc, TransactionTemplate transaction, ConfigurableApplicationContext context) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.context = context;
    }

    record Point(long id, double lat, double lng) {
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return;
        }
        int code = 0;
        try {
            int linked = refresh();
            System.out.println("신호 다시 계산: 신호 위치가 연결된 횡단보도 " + linked + "곳 (확정값 유지)");
        } catch (Exception e) {
            code = 1;
            System.err.println("신호 다시 계산 실패: " + e.getMessage());
        }
        int exit = code;
        System.exit(SpringApplication.exit(context, () -> exit));
    }

    /** 호출자의 적재 트랜잭션에 참여하므로 추정 갱신 실패 시 원본 교체도 되돌린다. */
    public int refresh() {
        return transaction.execute(st -> {
            // 확정 YES도 가까운 횡단보도를 고를 때 포함해야 다른 횡단보도에 신호를 잘못 나눠 주지 않는다.
            List<Point> crosswalks = jdbc.query("SELECT id, lat, lng FROM facility WHERE kind = 'CROSSWALK'",
                    (rs, i) -> new Point(rs.getLong(1), rs.getDouble(2), rs.getDouble(3)));
            List<Point> signals = jdbc.query("SELECT id, lat, lng FROM facility WHERE kind = 'PEDESTRIAN_SIGNAL'",
                    (rs, i) -> new Point(rs.getLong(1), rs.getDouble(2), rs.getDouble(3)));
            Set<Long> linked = linked(crosswalks, signals);
            jdbc.update("UPDATE facility SET signal_status = 'UNKNOWN' WHERE kind = 'CROSSWALK' AND signal_status = 'ESTIMATED'");
            List<Object[]> ids = linked.stream().map(id -> new Object[]{id}).toList();
            jdbc.batchUpdate("UPDATE facility SET signal_status = 'ESTIMATED' WHERE id = ? AND kind = 'CROSSWALK' AND (signal_status IS NULL OR signal_status <> 'YES')", ids);
            return linked.size();
        });
    }

    /** SignalLinks 와 같은 규칙. 5만 곳이라 칸으로 나눠 가까운 것만 비교한다 */
    static Set<Long> linked(List<Point> crosswalks, List<Point> signals) {
        Map<Long, List<Point>> grid = new HashMap<>();
        for (Point c : crosswalks) {
            grid.computeIfAbsent(key((long) Math.floor(c.lat() / CELL), (long) Math.floor(c.lng() / CELL)), k -> new ArrayList<>()).add(c);
        }
        Set<Long> linked = new HashSet<>();
        for (Point s : signals) {
            long y = (long) Math.floor(s.lat() / CELL), x = (long) Math.floor(s.lng() / CELL);
            Point best = null;
            double bestD = SignalLinks.LINK_M;
            for (long dy = -1; dy <= 1; dy++) {
                for (long dx = -1; dx <= 1; dx++) {
                    for (Point c : grid.getOrDefault(key(y + dy, x + dx), List.of())) {
                        double d = com.safeschoolroad.geo.Polyline.distance(new com.safeschoolroad.geo.LatLng(s.lat(), s.lng()), new com.safeschoolroad.geo.LatLng(c.lat(), c.lng()));
                        if (d < bestD || d == bestD && (best == null || c.id() < best.id())) {
                            bestD = d;
                            best = c;
                        }
                    }
                }
            }
            if (best != null) {
                linked.add(best.id());
            }
        }
        return linked;
    }

    private static long key(long y, long x) {
        return y * 10_000_000L + x;
    }
}
