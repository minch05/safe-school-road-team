package com.safeschoolroad.route.analysis;

import com.safeschoolroad.external.TmapRoute;
import com.safeschoolroad.facility.Facility;

import java.util.List;

// 후보 경로 하나를 분석한 결과. route(TMAP 응답)는 화면에 그리는 데만 쓰고 저장하지 않는다.
public record RouteAnalysis(
        TmapRoute route,
        List<KeyPoint> keyPoints,
        int crossingCount,          // TMAP이 건넌다고 한 곳 수
        int crossingsEstimated,     // 그중 "신호 있음 추정"
        int crossingsUnknown,       // 그중 "미확인" (데이터에 없는 곳 포함)
        int safetyFacilities,       // 50m 안 CCTV·비상벨 (같은 종류 20m 안은 하나로)
        List<String> schoolZones,   // 300m 안 어린이보호구역 대상 시설 이름
        List<String> accidentAreas, // 지나가는 사고다발지역 이름
        int turnsWithoutFacility,   // 꺾는 곳 중 30m 안에 쓸 시설이 없는 곳
        double maxGapMeters, List<MapFeature> mapFeatures) {

    public RouteAnalysis(TmapRoute route, List<KeyPoint> keyPoints, int crossingCount, int crossingsEstimated, int crossingsUnknown, int safetyFacilities, List<String> schoolZones, List<String> accidentAreas, int turnsWithoutFacility, double maxGapMeters) {
        this(route,keyPoints,crossingCount,crossingsEstimated,crossingsUnknown,safetyFacilities,schoolZones,accidentAreas,turnsWithoutFacility,maxGapMeters,List.of());
    }

    public record MapFeature(String id, String kind, String title, double lat, double lng, String detail, String source, String geometry, String baseDate, String baseDateLabel, String verification) {
        public MapFeature(String id, String kind, String title, double lat, double lng, String detail, String source, String geometry, String baseDate, String baseDateLabel) {
            this(id,kind,title,lat,lng,detail,source,geometry,baseDate,baseDateLabel, "");
        }
        public MapFeature(String id, String kind, String title, double lat, double lng, String detail, String source, String geometry) {
            this(id,kind,title,lat,lng,detail,source,geometry,"", "자료 기준일");
        }
    }

    public record KeyPoint(Facility facility, PointReason reason, double along, double distanceToLine, String turnDirection) {
        public KeyPoint(Facility facility, PointReason reason, double along, double distanceToLine) {
            this(facility, reason, along, distanceToLine, "");
        }
    }

    /** 후보끼리 "같은 경로"인지 볼 때 쓰는 값 — 지나는 시설(건너는 횡단보도·핵심 지점)이 같으면 같은 경로 */
    public List<String> facilitySignature() {
        return keyPoints.stream()
                .map(p -> p.facility().source() + ":" + p.facility().sourceId())
                .toList();
    }
}
