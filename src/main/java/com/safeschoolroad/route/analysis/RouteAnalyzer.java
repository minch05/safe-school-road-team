package com.safeschoolroad.route.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.external.TmapRoute;
import com.safeschoolroad.facility.Facility;
import com.safeschoolroad.facility.FacilityKind;
import com.safeschoolroad.facility.FacilityRepository;
import com.safeschoolroad.facility.RiskAreaRepository;
import com.safeschoolroad.facility.RiskAreaRepository.RiskArea;
import com.safeschoolroad.facility.SignalStatus;
import com.safeschoolroad.geo.LatLng;
import com.safeschoolroad.geo.Polyline;
import com.safeschoolroad.route.analysis.RouteAnalysis.KeyPoint;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 후보 경로 하나 분석: 위험요소 집계(FR-05) + 핵심 지점 고르기(FR-08, HANDOFF 5-3).
// 판정·계산은 여기(코드)가 하고, AI는 결과를 문장으로 바꾸기만 한다.
// 규칙은 사전 시험 코드(experiments/facility-count/select_points.py)를 그대로 옮겼다.
@Component
public class RouteAnalyzer {

    static final double NEAR_M = 50;         // 경로에서 이 거리 안의 시설을 본다
    static final double TURN_COVER_M = 30;   // 꺾는 곳·건너는 곳에서 이 거리 안의 시설
    static final double ONE_SPOT_M = 30;     // 경로를 따라 이 거리 안이면 같은 자리 → 하나만
    static final double FILL_NEAR_M = 25;    // 간격 채우기 시설은 경로에서 이 거리 안 (아이 눈에 띄는 범위)
    static final double FILL_M = 200;        // 지점 사이가 이보다 멀면 가운데에 시설을 넣는다
    static final double SAME_SPOT_M = 20;    // 안전시설 수를 셀 때 같은 종류가 이 거리 안이면 하나로
    static final double SCHOOL_ZONE_M = 300; // 어린이보호구역: 대상 시설에서 300m (도로교통법 기본 범위)

    // 꺾는 곳·간격 채우기 우선순위: 학교 > 아동안전지킴이집 > 비상벨 > CCTV
    private static final Map<FacilityKind, Integer> PRIORITY = Map.of(
            FacilityKind.SCHOOL, 0, FacilityKind.CROSSWALK, 1, FacilityKind.CHILD_SAFETY_HOUSE, 2, FacilityKind.EMERGENCY_BELL, 3, FacilityKind.CCTV, 4);

    private final FacilityRepository facilities;
    private final RiskAreaRepository riskAreas;
    private final ObjectMapper objectMapper;
    // 시험에서는 DB 없이 만들 수 있게 선택으로 둔다
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.safeschoolroad.facility.SchoolZoneCheck zoneCheck;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.safeschoolroad.walk.WalkNetwork walkNetwork;

    public RouteAnalyzer(FacilityRepository facilities, RiskAreaRepository riskAreas, ObjectMapper objectMapper) {
        this.facilities = facilities;
        this.riskAreas = riskAreas;
        this.objectMapper = objectMapper;
    }

    /** DB에서 경로 주변 시설을 꺼내 분석한다 */
    public RouteAnalysis analyze(TmapRoute route) {
        Polyline line = new Polyline(route.line());
        double[] near = line.bounds(NEAR_M + 10);
        List<Facility> nearby = facilities.findInBox(near[0], near[1], near[2], near[3], 50_000,
                FacilityKind.CROSSWALK, FacilityKind.SCHOOL, FacilityKind.CCTV, FacilityKind.EMERGENCY_BELL, FacilityKind.CHILD_SAFETY_HOUSE, FacilityKind.PEDESTRIAN_SIGNAL, FacilityKind.SECURITY_LIGHT);
        double[] zone = line.bounds(SCHOOL_ZONE_M + 10);
        List<Facility> zones = facilities.findInBox(zone[0], zone[1], zone[2], zone[3], 5_000, FacilityKind.SCHOOL_ZONE);
        // 대상 학교와 300m 넘게 어긋난 보호구역 좌표는 세지 않는다 (SchoolZoneCheck)
        if (zoneCheck != null) {
            zones = zoneCheck.keepTrusted(zones);
        }
        List<RiskArea> areas = riskAreas.findInBox(near[0], near[1], near[2], near[3]);
        return analyze(route, nearby, zones, areas);
    }

    // DB 없이도 시험할 수 있게 계산 부분을 나눴다
    RouteAnalysis analyze(TmapRoute route, List<Facility> nearby, List<Facility> schoolZones, List<RiskArea> areas) {
        Polyline line = new Polyline(route.line());

        // 신호 추정: 보행등마다 가장 가까운 횡단보도 하나에만 연결 (SignalLinks, 동작구 정답으로 비교해 고른 규칙)
        List<Facility> allCrosswalks = SignalLinks.ofKind(nearby, FacilityKind.CROSSWALK);
        List<Facility> signals = SignalLinks.ofKind(nearby, FacilityKind.PEDESTRIAN_SIGNAL);
        Set<Long> linked = SignalLinks.linkedCrosswalks(allCrosswalks, signals);

        List<Near> near = new ArrayList<>();
        for (Facility f : nearby) {
            if (!f.kind().isKeyPointKind()) continue;
            Polyline.Location loc = line.locate(f.lat(), f.lng());
            if (loc.distance() <= NEAR_M) {
                Facility effective = f;
                if (f.kind() == FacilityKind.CROSSWALK && f.signalStatus() != SignalStatus.YES) {
                    SignalStatus s = linked.contains(f.id()) ? SignalStatus.ESTIMATED : SignalStatus.UNKNOWN;
                    effective = new Facility(f.id(), f.kind(), f.name(), f.lat(), f.lng(), s, f.source(), f.sourceId(), f.baseDate());
                }
                near.add(new Near(effective, loc.distance(), loc.along()));
            }
        }
        List<Near> crosswalks = near.stream().filter(n -> n.f.kind() == FacilityKind.CROSSWALK).toList();

        List<Chosen> chosen = new ArrayList<>();

        List<RouteAnalysis.MapFeature> features = new ArrayList<>();
        // ① 건너는 횡단보도 (필수). 신호 상태도 여기서 센다
        int crossingCount = 0, estimated = 0;
        for (TmapRoute.Turn t : route.turns()) {
            if (!t.isCrossing()) {
                continue;
            }
            crossingCount++;
            LatLng tp = new LatLng(t.lat(), t.lng());
            Near best = crosswalks.stream()
                    .min(Comparator.comparingDouble(n -> Polyline.distance(tp, n.latLng())))
                    .filter(n -> Polyline.distance(tp, n.latLng()) <= TURN_COVER_M)
                    .orElse(null);
            boolean signalKnown = best != null && (best.f.signalStatus()==SignalStatus.ESTIMATED || best.f.signalStatus()==SignalStatus.YES);
            boolean nearbySignal = nearby.stream().anyMatch(f -> f.kind()==FacilityKind.PEDESTRIAN_SIGNAL && Polyline.distance(tp,new LatLng(f.lat(),f.lng()))<=TURN_COVER_M);
            boolean ambiguous = best != null && SignalLinks.onlySharedLights(best.f, allCrosswalks, signals, linked);
            String verification = ambiguous && !signalKnown ? "AMBIGUOUS_SIGNAL_LINK" : signalKnown ? "ESTIMATED" : best==null ? "UNMATCHED_CROSSWALK" : nearbySignal ? "UNLINKED_NEARBY_SIGNAL" : "NO_NEARBY_SIGNAL";
            String cause = signalKnown ? "" : ambiguous ? " 가까운 보행등이 다른 횡단보도에 더 가까워 이 건널목의 신호인지 구분하지 못했어요." : best==null ? " 건넘 위치와 횡단보도 자료를 연결하지 못했어요." : nearbySignal ? " 주변에 보행등 자료는 있지만 이 횡단보도의 신호인지 확인하지 못했어요." : " 주변 보행등 자료와 연결하지 못했어요. 자료 누락일 수 있어요.";
            String match = best == null ? "30m 안의 등록 횡단보도를 찾지 못했어요." : "등록 횡단보도와 약 "+Math.round(Polyline.distance(tp,best.latLng()))+"m 거리로 대조했어요.";
            features.add(new RouteAnalysis.MapFeature("crossing-"+crossingCount,"CROSSWALK","건너는 곳 "+crossingCount,t.lat(),t.lng(),(signalKnown?"신호 있음 추정 · 실제 보행 신호를 확인해 주세요. ":"신호 미확인 · 신호가 없다는 뜻이 아니에요. ")+match+cause,"TMAP 건넘 위치 + 서울시 횡단보도·보행등 위치 대조", "",best==null||best.f.baseDate()==null?"":best.f.baseDate().toString(),"횡단보도 원본 내려받은 날짜",verification));
            if (best != null) {
                add(chosen, best, PointReason.CROSSING);
                if (best.f.signalStatus() == SignalStatus.ESTIMATED || best.f.signalStatus() == SignalStatus.YES) {
                    estimated++;
                }
            }
        }

        // ② 꺾는 곳: 30m 안 시설 하나 (횡단보도는 쓰지 않는다 — 건너지 않는 횡단보도를 찍으면 아이가 거기서 건널 수 있다)
        int uncovered = 0;
        for (TmapRoute.Turn t : route.turns()) {
            if (!t.isTurn()) {
                continue;
            }
            LatLng tp = new LatLng(t.lat(), t.lng());
            Near best = near.stream()
                    .filter(n -> n.f.kind().isKeyPointKind() && n.f.kind() != FacilityKind.CROSSWALK && Polyline.distance(tp, n.latLng()) <= TURN_COVER_M)
                    .min(Comparator.<Near>comparingInt(n -> PRIORITY.get(n.f.kind()))
                            .thenComparingDouble(n -> Polyline.distance(tp, n.latLng())))
                    .orElse(null);
            if (best != null) {
                add(chosen, best, PointReason.TURN);
            } else {
                uncovered++;
            }
        }

        // ③ 간격 채우기: 지점 사이가 200m 넘으면 가운데 쪽에 경로 25m 안 시설
        List<Near> fillers = near.stream()
                .filter(n -> n.f.kind().isKeyPointKind() && n.f.kind() != FacilityKind.CROSSWALK && n.distance <= FILL_NEAR_M).toList();
        double length = line.length();
        boolean added = true;
        while (added) {
            added = false;
            chosen.sort(Comparator.comparingDouble(c -> c.n.along));
            List<Double> stops = stops(chosen, length);
            for (int i = 0; i + 1 < stops.size() && !added; i++) {
                double a = stops.get(i), b = stops.get(i + 1);
                if (b - a <= FILL_M) {
                    continue;
                }
                double mid = (a + b) / 2;
                Near best = fillers.stream()
                        .filter(n -> a + ONE_SPOT_M < n.along && n.along < b - ONE_SPOT_M)
                        // 가운데에서 40m 단위로 가까운 것 먼저, 같으면 우선순위
                        .min(Comparator.<Near>comparingLong(n -> (long) Math.floor(Math.abs(n.along - mid) / 40))
                                .thenComparingInt(n -> PRIORITY.get(n.f.kind())))
                        .orElse(null);
                if (best != null) {
                    int before = chosen.size();
                    add(chosen, best, PointReason.GAP);
                    added = chosen.size() > before;
                }
            }
        }
        chosen.sort(Comparator.comparingDouble(c -> c.n.along));
        List<Double> stops = stops(chosen, length);
        double maxGap = 0;
        for (int i = 0; i + 1 < stops.size(); i++) {
            maxGap = Math.max(maxGap, stops.get(i + 1) - stops.get(i));
        }

        List<KeyPoint> keyPoints = new ArrayList<>(chosen.stream()
                .map(c -> new KeyPoint(c.n.f, c.reason, c.n.along, c.n.distance)).toList());
        if (walkNetwork != null) {
            for (KeyPoint junction : walkNetwork.match(route)) {
                // 건넘 안내를 갈림길 안내로 바꾸지 않는다. 가까운 시설 기반 꺾음만 대체한다.
                if (keyPoints.stream().anyMatch(p -> p.reason() == PointReason.CROSSING && Math.abs(p.along()-junction.along())<30)) continue;
                if (keyPoints.stream().anyMatch(p -> p.reason()!=PointReason.TURN && Math.abs(p.along()-junction.along())<30)) continue;
                boolean replaced=keyPoints.removeIf(p -> p.reason()==PointReason.TURN && Math.abs(p.along()-junction.along())<30);
                keyPoints.add(junction);
                if (!replaced) uncovered=Math.max(0,uncovered-1);
                features.add(new RouteAnalysis.MapFeature("walk-"+junction.facility().sourceId(), "WALK_JUNCTION", "갈림길",
                        junction.facility().lat(),junction.facility().lng(),
                        "공개 도보망 연결 방향으로 계산한 꺾는 곳이에요. 2020년 기준 자료이므로 현재 길을 확인해 주세요.",
                        "서울특별시 도보 네트워크 · 공공누리 1유형", "", "2020", "원본 설명의 조사 기준연도"));
            }
            keyPoints.sort(Comparator.comparingDouble(KeyPoint::along));
            double previous=0; maxGap=0;
            for (KeyPoint p:keyPoints) { maxGap=Math.max(maxGap,p.along()-previous); previous=p.along(); }
            maxGap=Math.max(maxGap,line.length()-previous);
        }

        var zoneNames=schoolZonesNear(line,schoolZones);
        var accidentNames=accidentAreasCrossed(line,areas);
        Set<String> seenZones=new LinkedHashSet<>();
        for(Facility z:schoolZones) if(zoneNames.contains(z.name()) && line.locate(z.lat(),z.lng()).distance()<=SCHOOL_ZONE_M && seenZones.add(z.name()))
            features.add(facilityFeature(z,"SCHOOL_ZONE","대상 시설에서 300m 이내의 참고 위치예요. 실제 보호구역 도로 경계가 아니에요."));
        for(Near n:safetyLocations(near)) features.add(facilityFeature(n.f,"SAFETY","경로에서 50m 안의 시설 · 작동 여부와 자료 누락은 현장에서 확인해 주세요."));
        for(Facility f:nearby) if((f.kind()==FacilityKind.PEDESTRIAN_SIGNAL||f.kind()==FacilityKind.SECURITY_LIGHT)&&line.locate(f.lat(),f.lng()).distance()<=NEAR_M)
            features.add(facilityFeature(f,f.kind()==FacilityKind.PEDESTRIAN_SIGNAL?"SIGNAL":"LIGHT",f.kind()==FacilityKind.PEDESTRIAN_SIGNAL?"경로에서 50m 안의 보행등 위치예요. 건널목과 직접 연결된 정보가 아니며 신호 상태를 확정하지 않아요.":"경로에서 50m 안의 설치 위치예요. 밝기나 작동 여부를 확인한 자료가 아니에요. 지역별 자료 누락이 있어 비교 제안 순위에는 사용하지 않아요."));
        for(RiskArea area:areas) if(!accidentAreasCrossed(line,List.of(area)).isEmpty()){
            var rings=outerRings(area.geoJson());if(rings.isEmpty()||rings.get(0).isEmpty())continue;
            double lng=rings.get(0).stream().mapToDouble(v->v[0]).average().orElse(0),lat=rings.get(0).stream().mapToDouble(v->v[1]).average().orElse(0);
            String type=switch(area.kind()) {
                case "CHILD_ACCIDENT_SCHOOLZONE" -> "보호구역 내 어린이 사고";
                case "CHILD_PEDESTRIAN_ACCIDENT" -> "보행 어린이 사고";
                case "ELDERLY_PEDESTRIAN_ACCIDENT" -> "노인 보행자 사고";
                default -> "사고다발지역";
            };
            features.add(new RouteAnalysis.MapFeature("accident-"+area.id(),"ACCIDENT",type+" · "+area.name(),lat,lng,(area.baseYear()==null?"기준 연도 미확인":area.baseYear()+"년 사고 자료")+" · 경로와 겹치는 영역. 서로 겹친 영역도 있어 실제 장소 수와 다를 수 있어요.", "한국도로교통공단",area.geoJson()));
        }
        return new RouteAnalysis(route, keyPoints, crossingCount, estimated, crossingCount - estimated,
                safetyLocations(near).size(), zoneNames, accidentNames,
                uncovered, maxGap, List.copyOf(features));
    }

    private static RouteAnalysis.MapFeature facilityFeature(Facility f,String kind,String detail) {
        String source=switch(f.source()) {
            case "SEOUL_PED_SIGNAL" -> "서울시 보행등 위치";
            case "SCHOOL_ZONE_STD" -> "전국 어린이보호구역 대상시설";
            case "CCTV_STD" -> "공공데이터 CCTV 위치";
            case "BELL_STD" -> "공공데이터 비상벨 위치";
            case "BELL_DISTRICT" -> "구청 비상벨 위치 · 점검 기록 기준";
            case "SMART_POLE_LIGHT" -> "서울시 스마트폴 보안등 기능 · 2025년 12월 현황(날짜는 월말 대표값)";
            case "SECURITY_LIGHT_STD", "SECURITY_LIGHT_DISTRICT" -> "공공데이터 보안등 위치";
            default -> f.source();
        };
        return new RouteAnalysis.MapFeature("facility-"+f.id(),kind,f.kind().getLabel()+(f.name().isBlank()?"":" · "+f.name()),f.lat(),f.lng(),detail,source,"",f.baseDate()==null?"":f.baseDate().toString(),"자료 기준일");
    }

    // 같은 자리(경로를 따라 30m 안)는 하나만: 건너는 횡단보도 > 우선순위 높은 시설
    private static void add(List<Chosen> chosen, Near f, PointReason why) {
        for (int i = 0; i < chosen.size(); i++) {
            Chosen c = chosen.get(i);
            if (Math.abs(c.n.along - f.along) <= ONE_SPOT_M) {
                boolean crossingWins = why == PointReason.CROSSING && c.reason != PointReason.CROSSING;
                boolean higherPriority = c.reason != PointReason.CROSSING
                        && PRIORITY.get(f.f.kind()) < PRIORITY.get(c.n.f.kind());
                if (crossingWins || higherPriority) {
                    chosen.set(i, new Chosen(f, why));
                }
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

    // 안전시설(CCTV·비상벨): 같은 종류가 20m 안에 모여 있으면 하나로 (한 기둥의 카메라 여러 대 등)
    private static List<Near> safetyLocations(List<Near> near) {
        List<Near> kept = new ArrayList<>();
        for (Near n : near) {
            if (n.f.kind() != FacilityKind.CCTV && n.f.kind() != FacilityKind.EMERGENCY_BELL) {
                continue;
            }
            boolean dup = kept.stream().anyMatch(k -> k.f.kind() == n.f.kind()
                    && Polyline.distance(k.latLng(), n.latLng()) <= SAME_SPOT_M);
            if (!dup) {
                kept.add(n);
            }
        }
        return kept;
    }

    private static List<String> schoolZonesNear(Polyline line, List<Facility> zones) {
        Set<String> names = new LinkedHashSet<>();
        zones.stream()
                .map(z -> new Object[]{z, line.locate(z.lat(), z.lng())})
                .filter(o -> ((Polyline.Location) o[1]).distance() <= SCHOOL_ZONE_M)
                .sorted(Comparator.comparingDouble(o -> ((Polyline.Location) o[1]).along()))
                .forEach(o -> names.add(((Facility) o[0]).name()));
        return List.copyOf(names);
    }

    // 사고다발지역: 경로를 5m 간격으로 따라가며 영역 안에 들어가는 점이 있는지 본다
    private List<String> accidentAreasCrossed(Polyline line, List<RiskArea> areas) {
        List<String> hit = new ArrayList<>();
        for (RiskArea area : areas) {
            List<List<double[]>> rings = outerRings(area.geoJson());
            boolean inside = false;
            for (double along = 0; along <= line.length() && !inside; along += 5) {
                LatLng p = line.pointAt(along);
                inside = rings.stream().anyMatch(r -> contains(r, p.lng(), p.lat()));
            }
            if (inside) {
                hit.add(area.name());
            }
        }
        return hit;
    }

    // GeoJSON Polygon/MultiPolygon 의 바깥 테두리들 ([경도, 위도] 목록)
    private List<List<double[]>> outerRings(String geoJson) {
        List<List<double[]>> rings = new ArrayList<>();
        try {
            JsonNode g = objectMapper.readTree(geoJson);
            JsonNode coords = g.path("coordinates");
            List<JsonNode> polygons = new ArrayList<>();
            if ("Polygon".equals(g.path("type").asText())) {
                polygons.add(coords);
            } else if ("MultiPolygon".equals(g.path("type").asText())) {
                coords.forEach(polygons::add);
            }
            for (JsonNode poly : polygons) {
                List<double[]> ring = new ArrayList<>();
                poly.get(0).forEach(c -> ring.add(new double[]{c.get(0).asDouble(), c.get(1).asDouble()}));
                rings.add(ring);
            }
        } catch (Exception ignore) {
            // 모양을 읽지 못한 영역은 건너뛴다
        }
        return rings;
    }

    // 점이 다각형 안에 있는지 (반직선 교차 수)
    static boolean contains(List<double[]> ring, double x, double y) {
        boolean in = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            double[] a = ring.get(i), b = ring.get(j);
            if ((a[1] > y) != (b[1] > y) && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) {
                in = !in;
            }
        }
        return in;
    }

    private record Near(Facility f, double distance, double along) {
        LatLng latLng() {
            return new LatLng(f.lat(), f.lng());
        }
    }

    private record Chosen(Near n, PointReason reason) {
    }
}
