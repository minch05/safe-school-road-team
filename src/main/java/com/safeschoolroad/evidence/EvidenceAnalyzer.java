package com.safeschoolroad.evidence;

import com.safeschoolroad.evidence.EvidenceData.Area;
import com.safeschoolroad.evidence.EvidenceData.Coverage;
import com.safeschoolroad.evidence.EvidenceData.Facility;
import com.safeschoolroad.evidence.RouteEvidence.Accident;
import com.safeschoolroad.evidence.RouteEvidence.Crossing;
import com.safeschoolroad.evidence.RouteEvidence.KeyPoint;
import com.safeschoolroad.route.Point;
import com.safeschoolroad.route.TmapGateway;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * 후보 경로별 근거 분석(FR-05)과 안내 지점 고르기(FR-08). 개인 개발본 RouteAnalyzer의 규칙을 팀 저장소 구조로 옮겼다.
 * 판정·계산은 코드가 하고, 안전 점수는 만들지 않는다.
 */
@Component
public class EvidenceAnalyzer {
    static final double NEAR_M = 50;          // 경로에서 이 거리 안의 시설을 본다
    static final double MATCH_M = 30;         // 건넘·꺾음 위치에서 이 거리 안의 시설
    static final double ONE_SPOT_M = 30;      // 경로를 따라 이 거리 안이면 같은 자리 → 하나만
    static final double FILL_NEAR_M = 25;     // 간격 채우기 시설은 경로에서 이 거리 안
    static final double FILL_M = 200;         // 지점 사이가 이보다 멀면 가운데에 시설을 넣는다
    static final double SAME_SPOT_M = 20;     // 같은 종류 안전시설이 이 거리 안이면 하나로
    static final double SCHOOL_ZONE_M = 300;  // 어린이보호구역 대상 시설에서 300m
    static final List<String> NEAR_KINDS = List.of("CROSSWALK", "SCHOOL", "CCTV", "EMERGENCY_BELL");
    static final List<String> ALL_KINDS = List.of("CROSSWALK", "SCHOOL", "SCHOOL_ZONE", "CCTV", "EMERGENCY_BELL", "SECURITY_LIGHT");
    private static final Map<String, Integer> PRIORITY = Map.of("SCHOOL", 0, "CROSSWALK", 1, "EMERGENCY_BELL", 3, "CCTV", 4);
    private static final Map<String, String> KIND_LABEL = Map.of("CROSSWALK", "횡단보도", "SCHOOL", "학교", "SCHOOL_ZONE", "어린이보호구역",
            "CCTV", "CCTV", "EMERGENCY_BELL", "안전비상벨", "SECURITY_LIGHT", "보안등");

    private final EvidenceData data;

    public EvidenceAnalyzer(EvidenceData data) { this.data = data; }

    /** DB에서 경로 주변 자료를 꺼내 분석한다 */
    public RouteEvidence analyze(String candidateId, TmapGateway.RouteOption route) {
        GeoLine line = new GeoLine(route.line());
        List<Facility> nearby = data.facilities(line.bounds(NEAR_M + 10), NEAR_KINDS, 50_000);
        List<Facility> zones = data.facilities(line.bounds(SCHOOL_ZONE_M + 10), List.of("SCHOOL_ZONE"), 5_000);
        List<Area> areas = data.areas(line.bounds(NEAR_M));
        return analyze(candidateId, route, nearby, zones, areas, data.coverage());
    }

    /** DB 없이 시험할 수 있게 계산 부분을 나눴다 */
    public static RouteEvidence analyze(String candidateId, TmapGateway.RouteOption route, List<Facility> nearbyAll,
                                        List<Facility> zones, List<Area> areas, List<Coverage> coverage) {
        GeoLine line = new GeoLine(route.line());
        List<Near> near = new ArrayList<>();
        for (Facility f : nearbyAll) {
            GeoLine.Location loc = line.locate(f.lat(), f.lng());
            if (loc.distance() <= NEAR_M) near.add(new Near(f, loc.distance(), loc.along()));
        }
        List<Near> crosswalks = near.stream().filter(n -> n.f.kind().equals("CROSSWALK")).toList();
        List<Chosen> chosen = new ArrayList<>();

        // ① 실제 건넘: TMAP이 건넌다고 한 곳마다 30m 안의 등록 횡단보도와 대조한다
        List<Crossing> crossings = new ArrayList<>();
        int estimated = 0, unknown = 0, unmatched = 0;
        for (TmapGateway.Turn t : route.turns()) {
            if (!t.isCrossing()) continue;
            Point tp = t.at();
            Near best = crosswalks.stream()
                    .min(Comparator.comparingDouble(n -> n.distanceTo(tp)))
                    .filter(n -> n.distanceTo(tp) <= MATCH_M).orElse(null);
            int seq = crossings.size() + 1;
            if (best == null) {
                unmatched++;
                crossings.add(new Crossing(seq, tp.lat(), tp.lng(), "UNMATCHED", null, null,
                        "30m 안의 등록 횡단보도를 찾지 못했어요. 신호를 확인하지 못했어요."));
                continue;
            }
            boolean est = "ESTIMATED".equals(best.f.signalStatus());
            if (est) estimated++; else unknown++;
            crossings.add(new Crossing(seq, tp.lat(), tp.lng(), est ? "ESTIMATED" : "UNKNOWN", best.f.originalId(),
                    (int) Math.round(best.distanceTo(tp)),
                    est ? "신호 있음 추정 · 실제 신호를 보고 건너요." : "신호 미확인 · 신호가 없다는 뜻이 아니에요."));
            add(chosen, best, "CROSSING");
        }

        // ② 꺾는 곳: 30m 안 시설 하나. 건너지 않는 횡단보도는 쓰지 않는다
        int uncovered = 0;
        for (TmapGateway.Turn t : route.turns()) {
            if (!t.isTurn()) continue;
            Point tp = t.at();
            Near best = near.stream()
                    .filter(n -> !n.f.kind().equals("CROSSWALK") && n.distanceTo(tp) <= MATCH_M)
                    .min(Comparator.<Near>comparingInt(n -> PRIORITY.get(n.f.kind())).thenComparingDouble(n -> n.distanceTo(tp)))
                    .orElse(null);
            if (best != null) add(chosen, best, "TURN"); else uncovered++;
        }

        // ③ 간격 채우기: 지점 사이가 200m를 넘으면 가운데 쪽에 경로 25m 안 시설
        List<Near> fillers = near.stream().filter(n -> !n.f.kind().equals("CROSSWALK") && n.distance <= FILL_NEAR_M).toList();
        double length = line.length();
        boolean added = true;
        while (added) {
            added = false;
            chosen.sort(Comparator.comparingDouble(c -> c.n.along));
            List<Double> stops = stops(chosen, length);
            for (int i = 0; i + 1 < stops.size() && !added; i++) {
                double a = stops.get(i), b = stops.get(i + 1);
                if (b - a <= FILL_M) continue;
                double mid = (a + b) / 2;
                Near best = fillers.stream()
                        .filter(n -> a + ONE_SPOT_M < n.along && n.along < b - ONE_SPOT_M)
                        .min(Comparator.<Near>comparingLong(n -> (long) Math.floor(Math.abs(n.along - mid) / 40))
                                .thenComparingInt(n -> PRIORITY.get(n.f.kind())))
                        .orElse(null);
                if (best != null) {
                    int before = chosen.size();
                    add(chosen, best, "GAP");
                    added = chosen.size() > before;
                }
            }
        }
        chosen.sort(Comparator.comparingDouble(c -> c.n.along));
        List<Double> stops = stops(chosen, length);
        double maxGap = 0;
        for (int i = 0; i + 1 < stops.size(); i++) maxGap = Math.max(maxGap, stops.get(i + 1) - stops.get(i));

        List<KeyPoint> keyPoints = new ArrayList<>();
        for (Chosen c : chosen) {
            Facility f = c.n.f;
            keyPoints.add(new KeyPoint(keyPoints.size() + 1, f.kind(), f.name() == null ? "" : f.name(), c.reason, f.lat(), f.lng(),
                    (int) Math.round(c.n.along), f.kind().equals("CROSSWALK") ? signalOf(f) : null, f.source(), f.originalId()));
        }

        // 안전시설: 같은 종류 20m 안은 하나로
        List<Near> safety = new ArrayList<>();
        for (Near n : near) {
            if (!n.f.kind().equals("CCTV") && !n.f.kind().equals("EMERGENCY_BELL")) continue;
            boolean dup = safety.stream().anyMatch(k -> k.f.kind().equals(n.f.kind())
                    && GeoLine.distance(k.f.lat(), k.f.lng(), n.f.lat(), n.f.lng()) <= SAME_SPOT_M);
            if (!dup) safety.add(n);
        }
        int cctv = (int) safety.stream().filter(n -> n.f.kind().equals("CCTV")).count();
        int bells = safety.size() - cctv;

        Set<String> schools = new LinkedHashSet<>();
        near.stream().filter(n -> n.f.kind().equals("SCHOOL")).sorted(Comparator.comparingDouble(n -> n.along))
                .forEach(n -> schools.add(n.f.name()));
        Set<String> zoneNames = new LinkedHashSet<>();
        zones.stream().map(z -> new Near(z, line.locate(z.lat(), z.lng()).distance(), line.locate(z.lat(), z.lng()).along()))
                .filter(n -> n.distance <= SCHOOL_ZONE_M).sorted(Comparator.comparingDouble(n -> n.along))
                .forEach(n -> zoneNames.add(n.f.name()));

        List<Accident> accidents = new ArrayList<>();
        for (Area area : areas) {
            if (area.ring().size() < 4) continue;
            boolean inside = false;
            for (double along = 0; along <= length && !inside; along += 5) {
                Point p = line.pointAt(along);
                inside = GeoLine.contains(area.ring(), p.lng(), p.lat());
            }
            if (inside) accidents.add(new Accident(area.kind(), accidentLabel(area.kind()), area.name(), area.baseYear(), area.source()));
        }

        // 확인 범위: 근처 자료가 있는 구와, 그 구에서 적재되지 않은 자료 종류
        Set<String> districts = new TreeSet<>();
        nearbyAll.forEach(f -> districts.add(f.district()));
        zones.forEach(f -> { if (line.locate(f.lat(), f.lng()).distance() <= SCHOOL_ZONE_M) districts.add(f.district()); });
        Set<String> missing = new LinkedHashSet<>();
        Set<String> sources = new LinkedHashSet<>();
        for (Coverage c : coverage) {
            if (!districts.contains(c.district())) continue;
            if (!"LOADED".equals(c.status())) missing.add(c.district() + " " + KIND_LABEL.getOrDefault(c.kind(), c.kind()));
            else sources.add(c.source() + (c.downloaded() == null ? "" : " (원본 내려받은 날짜 " + c.downloaded() + ")"));
        }
        if (!accidents.isEmpty()) sources.add(accidents.get(0).source() + " (2023년 사고 기준)");

        List<String> limitations = new ArrayList<>();
        if (districts.isEmpty()) limitations.add("이 경로 주변에는 적재된 공공자료가 없어요. 지원 지역(12개 구) 밖이거나 자료가 비어 있어요.");
        limitations.add("신호는 확정 자료가 없어 '추정' 또는 '미확인'으로만 표시해요. 실제 신호를 보고 건너요.");
        if (!missing.isEmpty()) limitations.add("적재되지 않은 자료: " + String.join(", ", missing));
        limitations.add("시설 위치는 공공자료 기준이며 작동 여부나 현재 상태는 확인하지 않았어요.");
        limitations.add("어린이보호구역은 대상 시설 위치에서 300m 안으로 본 참고 값이에요. 실제 구역 경계가 아니에요.");
        if (route.turns().stream().noneMatch(TmapGateway.Turn::isCrossing))
            limitations.add("TMAP이 이 경로에서 건너는 곳을 알려 주지 않았어요. 건넘이 0건이어도 실제 길에서 다시 확인해요.");

        return new RouteEvidence(candidateId, (int) Math.round(length),
                new RouteEvidence.Crossings(crossings.size(), estimated, unknown, unmatched, List.copyOf(crossings)),
                new RouteEvidence.Safety(cctv, bells, (int) NEAR_M), List.copyOf(schools), List.copyOf(zoneNames),
                List.copyOf(accidents), List.copyOf(keyPoints), uncovered, (int) Math.round(maxGap),
                new RouteEvidence.Coverage((int) NEAR_M, List.copyOf(districts), List.copyOf(missing), List.copyOf(sources)),
                List.copyOf(limitations));
    }

    static String signalOf(Facility f) {
        return "ESTIMATED".equals(f.signalStatus()) ? "ESTIMATED" : "UNKNOWN";
    }

    static String accidentLabel(String kind) {
        return switch (kind) {
            case "CHILD_ACCIDENT_SCHOOLZONE" -> "보호구역 안 어린이 사고 다발 지역";
            case "CHILD_PEDESTRIAN_ACCIDENT" -> "보행 어린이 사고 다발 지역";
            case "ELDERLY_PEDESTRIAN_ACCIDENT" -> "노인 보행자 사고 다발 지역";
            default -> "사고 다발 지역";
        };
    }

    // 같은 자리(경로를 따라 30m 안)는 하나만: 건너는 횡단보도 > 우선순위 높은 시설
    private static void add(List<Chosen> chosen, Near f, String why) {
        for (int i = 0; i < chosen.size(); i++) {
            Chosen c = chosen.get(i);
            if (Math.abs(c.n.along - f.along) <= ONE_SPOT_M) {
                boolean crossingWins = why.equals("CROSSING") && !c.reason.equals("CROSSING");
                boolean higher = !c.reason.equals("CROSSING") && PRIORITY.get(f.f.kind()) < PRIORITY.get(c.n.f.kind());
                if (crossingWins || higher) chosen.set(i, new Chosen(f, why));
                return;
            }
        }
        chosen.add(new Chosen(f, why));
    }

    private static List<Double> stops(List<Chosen> chosen, double length) {
        List<Double> stops = new ArrayList<>();
        stops.add(0.0);
        chosen.forEach(c -> stops.add(c.n.along));
        stops.add(length);
        return stops;
    }

    private record Near(Facility f, double distance, double along) {
        double distanceTo(Point p) { return GeoLine.distance(f.lat(), f.lng(), p.lat(), p.lng()); }
    }

    private record Chosen(Near n, String reason) { }
}
