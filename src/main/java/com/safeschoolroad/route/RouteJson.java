package com.safeschoolroad.route;

import com.safeschoolroad.external.TmapRoute;
import com.safeschoolroad.facility.Facility;
import com.safeschoolroad.facility.SignalStatus;
import com.safeschoolroad.route.RouteCandidateService.Candidate;
import com.safeschoolroad.route.analysis.RouteAnalysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 화면(JS)으로 보내는 JSON 모양. 경로선 좌표는 그리기용으로만 보내고 서버에 저장하지 않는다.
public final class RouteJson {

    private RouteJson() {
    }

    public static List<List<Double>> line(TmapRoute route) {
        return route.line().stream().map(p -> List.of(round(p.lat()), round(p.lng()))).toList();
    }

    public static Map<String, Object> candidate(Candidate c) {
        RouteAnalysis a = c.analysis();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("option", c.option());
        m.put("options", c.options());
        m.put("label", c.label());
        m.put("distance", a.route().distanceMeters());
        m.put("time", a.route().timeSeconds());
        m.put("line", line(a.route()));
        m.put("crossings", Map.of("total", a.crossingCount(), "estimated", a.crossingsEstimated(),
                "unknown", a.crossingsUnknown(), "causes", a.mapFeatures().stream().filter(f -> f.kind().equals("CROSSWALK") && !f.verification().isBlank()).collect(java.util.stream.Collectors.groupingBy(RouteAnalysis.MapFeature::verification, java.util.stream.Collectors.counting()))));
        m.put("crime", c.crime());
        m.put("mapFeatures",a.mapFeatures());
        m.put("searchDescription", c.options().stream().map(o -> switch(o){case 0 -> "TMAP의 기본 보행 탐색 결과예요. 공공데이터 안전 순위가 아니에요.";case 4 -> "대로를 우선해 탐색한 결과예요. 모든 구간이 큰길이거나 보도가 넓다는 뜻은 아니에요.";default -> "최단 옵션으로 탐색한 결과예요. 실제 제공된 거리와 시간을 함께 확인해 주세요.";}).toList());
        m.put("safety", a.safetyFacilities());
        m.put("schoolZones", a.schoolZones());
        m.put("accidents", a.accidentAreas());
        m.put("turnsWithoutFacility", a.turnsWithoutFacility());
        m.put("points", a.keyPoints().stream().map(kp -> point(kp.facility(), kp.reason().getLabel())).toList());
        return m;
    }

    private static Map<String, Object> point(Facility f, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", f.kind().name());
        m.put("kindLabel", f.kind().getLabel());
        m.put("name", f.name());
        m.put("signal", signalLabel(f.signalStatus()));
        m.put("reason", reason);
        m.put("lat", f.lat());
        m.put("lng", f.lng());
        return m;
    }

    public static Map<String, Object> draftPoint(RouteDraft.Point p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", p.seq());
        m.put("kind", p.kind().name());
        m.put("kindLabel", p.kind().getLabel());
        m.put("name", p.name());
        m.put("signal", signalLabel(p.signalStatus()));
        m.put("reason", p.reason().getLabel());
        m.put("text", p.guideText());
        m.put("lat", p.lat());
        m.put("lng", p.lng());
        return m;
    }

    public static Map<String, Object> routePoint(RoutePoint p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", p.getSeq());
        m.put("kind", p.getKind().name());
        m.put("kindLabel", p.getKind().getLabel());
        m.put("name", p.getName());
        m.put("signal", signalLabel(p.getSignalStatus()));
        m.put("reason", p.getReason().getLabel());
        m.put("text", p.getGuideText());
        m.put("edited", p.isGuideEdited());
        m.put("lat", p.getLat());
        m.put("lng", p.getLng());
        return m;
    }

    public static String signalLabel(SignalStatus s) {
        return s == null ? "" : s.getLabel();
    }

    private static double round(double v) {
        return Math.round(v * 1e6) / 1e6;
    }
}
