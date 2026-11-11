package com.safeschoolroad.facility.load;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 서울시 보행등 위치. "이 횡단보도 10m 안에 보행등이 있나?"를 빨리 묻기 위해
// 약 100m 칸으로 나눠 담아 두고, 주변 9칸만 본다.
final class PedestrianLights {

    private static final double CELL_DEG = 0.001; // 약 100m
    private static final double M_PER_DEG_LAT = 110540;
    private static final double M_PER_DEG_LNG = 88200; // 서울(위도 37.5도) 기준. count.py 와 같다

    private final Map<Long, List<double[]>> cells = new HashMap<>();
    private int size;
    private final java.util.Set<String> coordinates = new java.util.HashSet<>();

    void add(double lat, double lng) {
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || lat < 37.4 || lat > 37.73 || lng < 126.75 || lng > 127.2) return;
        if (!coordinates.add(lat + ":" + lng)) return;
        cells.computeIfAbsent(key(cell(lat), cell(lng)), k -> new ArrayList<>()).add(new double[]{lat, lng});
        size++;
    }

    int size() {
        return size;
    }

    List<FacilityRow> facilityRows() {
        return cells.values().stream().flatMap(List::stream)
                .sorted(java.util.Comparator.<double[]>comparingDouble(p->p[0]).thenComparingDouble(p->p[1]))
                .map(p->new FacilityRow(com.safeschoolroad.facility.FacilityKind.PEDESTRIAN_SIGNAL,"",p[0],p[1],null,"","SEOUL_PED_SIGNAL",p[0]+":"+p[1],null)).toList();
    }

    boolean anyWithin(double lat, double lng, double meters) {
        long cy = cell(lat);
        long cx = cell(lng);
        for (long dy = -1; dy <= 1; dy++) {
            for (long dx = -1; dx <= 1; dx++) {
                for (double[] p : cells.getOrDefault(key(cy + dy, cx + dx), List.of())) {
                    double dLat = (lat - p[0]) * M_PER_DEG_LAT;
                    double dLng = (lng - p[1]) * M_PER_DEG_LNG;
                    if (Math.hypot(dLat, dLng) <= meters) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static long cell(double deg) {
        return (long) Math.floor(deg / CELL_DEG);
    }

    private static long key(long y, long x) {
        return y * 1_000_000L + x;
    }
}
