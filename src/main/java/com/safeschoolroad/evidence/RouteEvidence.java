package com.safeschoolroad.evidence;

import java.util.List;

/**
 * 후보 경로 하나의 근거 (FR-05). 안전 점수나 '가장 안전' 판단은 넣지 않는다.
 * 숫자는 공공자료에서 확인한 범위 안의 사실이고, 자료가 없거나 확인하지 못한 것은 limitations에 적는다.
 */
public record RouteEvidence(
        String candidateId,
        int lengthM,
        Crossings crossings,
        Safety safety,
        List<String> schools,
        List<String> schoolZones,
        List<Accident> accidentAreas,
        List<KeyPoint> keyPoints,
        int turnsWithoutFacility,
        int maxGapM,
        Coverage coverage,
        List<String> limitations) {

    /** TMAP이 건넌다고 한 곳(실제 건넘) 기준. 신호는 확정 자료가 없어 추정/미확인만 있다. */
    public record Crossings(int count, int signalEstimated, int signalUnknown, int unmatched, List<Crossing> items) { }

    /** status: ESTIMATED(보행 신호 위치와 연결된 횡단보도) · UNKNOWN(연결 못 함) · UNMATCHED(30m 안 등록 횡단보도 없음) */
    public record Crossing(int seq, double lat, double lng, String signal, String crosswalkId, Integer matchDistanceM, String note) { }

    /** 경로 50m 안 CCTV·비상벨. 같은 종류가 20m 안에 모이면 한 곳으로 센다. 작동 여부는 확인하지 않은 값. */
    public record Safety(int cctv, int emergencyBell, int radiusM) { }

    public record Accident(String kind, String label, String name, Integer baseYear, String source) { }

    /** 안내 지점 후보. 좌표·이름은 공공자료 원본 값이고 TMAP 좌표가 아니다. reason: CROSSING · TURN · GAP */
    public record KeyPoint(int seq, String kind, String name, String reason, double lat, double lng, int alongM,
                           String signalStatus, String source, String originalId) { }

    /** checkedRadiusM: 시설을 찾은 경로 양옆 거리. districts: 근처 자료가 있는 구. missingKinds: 이 구들에서 적재되지 않은 자료 종류. */
    public record Coverage(int checkedRadiusM, List<String> districts, List<String> missingKinds, List<String> sources) { }
}
