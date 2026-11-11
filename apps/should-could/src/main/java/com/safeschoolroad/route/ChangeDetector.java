package com.safeschoolroad.route;

import com.safeschoolroad.geo.LatLng;
import com.safeschoolroad.geo.Polyline;

import java.util.ArrayList;
import java.util.List;

// 경로 달라짐 확인 (FR-22, HANDOFF 5-4). 선을 다시 받아 올 때 그 자리에서 판정한다. 아래 둘 중 하나라도 해당하면 달라짐.
//  1. 확정 때보다 20m 넘게 멀어진 지점이 2곳 이상
//  2. 우회 비율 1.15 초과 = 다시 받은 선의 길이 ÷ (출발지 → 저장 지점들 → 도착지 직선 거리 합)
//     기준 길이는 우리가 저장한 좌표로만 계산하므로 TMAP 데이터를 저장하지 않아도 된다.
public final class ChangeDetector {

    static final double MOVED_M = 20;
    static final int MOVED_COUNT = 2;
    static final double DETOUR_RATIO = 1.15;

    private ChangeDetector() {
    }

    public record SavedPoint(double lat, double lng, double distanceAtConfirm) {
    }

    public record Result(boolean changed, int movedPoints, double detourRatio) {
    }

    public static Result check(LatLng start, LatLng end, List<SavedPoint> points, List<LatLng> newLine) {
        Polyline line = new Polyline(newLine);
        int moved = 0;
        for (SavedPoint p : points) {
            double now = line.locate(p.lat(), p.lng()).distance();
            if (now - p.distanceAtConfirm() > MOVED_M) {
                moved++;
            }
        }
        List<LatLng> stops = new ArrayList<>();
        stops.add(start);
        points.forEach(p -> stops.add(new LatLng(p.lat(), p.lng())));
        stops.add(end);
        double straight = 0;
        for (int i = 1; i < stops.size(); i++) {
            straight += Polyline.distance(stops.get(i - 1), stops.get(i));
        }
        double ratio = straight <= 0 ? 1 : line.length() / straight;
        return new Result(moved >= MOVED_COUNT || ratio > DETOUR_RATIO, moved, ratio);
    }

    /** 경유지: 저장된 횡단보도 중 고르게 최대 5곳 (길옆 시설은 경유지로 쓰지 않는다, HANDOFF 5-4) */
    public static <T> List<T> spread(List<T> items, int k) {
        if (items.size() <= k) {
            return items;
        }
        List<T> out = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            out.add(items.get((int) Math.round(i * (items.size() - 1) / (double) (k - 1))));
        }
        return out;
    }
}
