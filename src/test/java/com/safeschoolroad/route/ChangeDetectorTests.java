package com.safeschoolroad.route;

import com.safeschoolroad.geo.LatLng;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 경로 달라짐 판정 (HANDOFF 5-4)
class ChangeDetectorTests {

    private static final double LAT = 37.59, LNG0 = 127.08;

    private static LatLng at(double eastM, double northM) {
        return new LatLng(LAT + northM / 110_540.0, LNG0 + eastM / 88_200.0);
    }

    private static ChangeDetector.SavedPoint saved(double eastM, double northM, double distAtConfirm) {
        LatLng p = at(eastM, northM);
        return new ChangeDetector.SavedPoint(p.lat(), p.lng(), distAtConfirm);
    }

    private final List<ChangeDetector.SavedPoint> points = List.of(
            saved(150, 10, 10), saved(300, 20, 20), saved(450, 5, 5));

    @Test
    void 같은_길이면_달라지지_않음() {
        var r = ChangeDetector.check(at(0, 0), at(600, 0), points, List.of(at(0, 0), at(600, 0)));
        assertThat(r.changed()).isFalse();
        assertThat(r.movedPoints()).isZero();
    }

    @Test
    void 보도만_바뀌어_조금_멀어진_것은_헛경보를_내지_않는다() {
        // 길 건너편 보도로 15m 옮겨짐 → 지점마다 15m 정도만 멀어짐
        var r = ChangeDetector.check(at(0, 0), at(600, 0), points, List.of(at(0, -15), at(600, -15)));
        assertThat(r.changed()).isFalse();
    }

    @Test
    void 지점_2곳_이상이_20m_넘게_멀어지면_달라짐() {
        // 가운데가 북쪽 100m로 크게 돌아감
        var line = List.of(at(0, 0), at(100, 0), at(150, 100), at(450, 100), at(500, 0), at(600, 0));
        var r = ChangeDetector.check(at(0, 0), at(600, 0), points, line);
        assertThat(r.movedPoints()).isGreaterThanOrEqualTo(2);
        assertThat(r.changed()).isTrue();
    }

    @Test
    void 지점_사이에서_옆으로_빠졌다_돌아오는_우회는_우회_비율로_잡는다() {
        // 지점은 모두 선 위에 그대로 있지만 사이에서 남쪽으로 200m 빠졌다 돌아옴
        var line = List.of(at(0, 0), at(150, 10), at(200, -200), at(250, 0), at(300, 20), at(450, 5), at(600, 0));
        var r = ChangeDetector.check(at(0, 0), at(600, 0), points, line);
        assertThat(r.movedPoints()).isLessThan(2);
        assertThat(r.detourRatio()).isGreaterThan(1.15);
        assertThat(r.changed()).isTrue();
    }

    @Test
    void 경유지는_고르게_5곳까지() {
        assertThat(ChangeDetector.spread(List.of(1, 2, 3), 5)).containsExactly(1, 2, 3);
        assertThat(ChangeDetector.spread(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9), 5)).containsExactly(1, 3, 5, 7, 9);
    }
}
