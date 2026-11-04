package com.safeschoolroad.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.evidence.EvidenceData.Area;
import com.safeschoolroad.evidence.EvidenceData.Coverage;
import com.safeschoolroad.evidence.EvidenceData.Facility;
import com.safeschoolroad.route.Point;
import com.safeschoolroad.route.TmapGateway;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** DB 없이 계산 규칙만 시험한다. 좌표는 합성 값이다. */
class EvidenceAnalyzerTests {
    // 위도 37.59를 따라 동쪽으로 약 880m 가는 직선 경로
    static final List<Point> LINE = List.of(new Point(37.59, 127.080), new Point(37.59, 127.085), new Point(37.59, 127.090));
    static final TmapGateway.RouteOption ROUTE = new TmapGateway.RouteOption(0, LINE, 900, 700, List.of(
            new TmapGateway.Turn(new Point(37.59, 127.0830), 211),   // 건넘 1: 등록 횡단보도와 대조됨
            new TmapGateway.Turn(new Point(37.59, 127.0870), 12),    // 좌회전
            new TmapGateway.Turn(new Point(37.59, 127.0895), 212))); // 건넘 2: 근처 등록 횡단보도 없음

    static Facility f(long id, String kind, String name, double lat, double lng, String signal) {
        return new Facility(id, kind, name, "중랑구", lat, lng, signal, "시험 출처", "id-" + id, "2026-10-02");
    }

    static final List<Facility> NEARBY = List.of(
            f(1, "CROSSWALK", "", 37.59001, 127.08301, "ESTIMATED"),
            f(2, "CROSSWALK", "", 37.59010, 127.08550, "UNKNOWN"),   // 건너지 않는 횡단보도
            f(3, "CCTV", "", 37.59005, 127.08700, null),
            f(4, "CCTV", "", 37.59008, 127.08705, null),            // 3번과 같은 자리(20m 안) → 한 곳
            f(5, "EMERGENCY_BELL", "", 37.59003, 127.08650, null),
            f(6, "SCHOOL", "테스트초등학교", 37.59020, 127.08850, null),
            f(7, "CCTV", "", 37.59500, 127.08500, null));           // 경로에서 550m → 제외
    static final List<Facility> ZONES = List.of(f(8, "SCHOOL_ZONE", "테스트초", 37.592, 127.085, null));
    static final List<Area> AREAS = List.of(new Area(1, "CHILD_PEDESTRIAN_ACCIDENT", "시험 사고 지역", "중랑구", 2023, "도로교통공단",
            "a-1", List.of(new double[]{127.0875, 37.5895}, new double[]{127.0880, 37.5895}, new double[]{127.0880, 37.5905},
            new double[]{127.0875, 37.5905}, new double[]{127.0875, 37.5895})));
    static final List<Coverage> COVERAGE = List.of(
            new Coverage("중랑구", "CROSSWALK", 1985, "LOADED", "서울시 교통안전시설물 횡단보도 OA-15554", "2026-10-02"),
            new Coverage("중랑구", "SECURITY_LIGHT", 0, "NO_DATA", "전국보안등정보표준데이터", "2026-09-18"),
            new Coverage("강남구", "SECURITY_LIGHT", 0, "NO_DATA", "전국보안등정보표준데이터", "2026-09-18"));

    @Test void countsRealCrossingsWithSignalStatusAndUnmatched() {
        var e = EvidenceAnalyzer.analyze("candidate-0", ROUTE, NEARBY, ZONES, AREAS, COVERAGE);
        assertEquals(2, e.crossings().count());
        assertEquals(1, e.crossings().signalEstimated());
        assertEquals(0, e.crossings().signalUnknown());
        assertEquals(1, e.crossings().unmatched());
        assertEquals("ESTIMATED", e.crossings().items().get(0).signal());
        assertEquals("id-1", e.crossings().items().get(0).crosswalkId());
        assertEquals("UNMATCHED", e.crossings().items().get(1).signal());
    }

    @Test void countsSafetyFacilitiesOnceAndFindsSchoolsZonesAndAccidents() {
        var e = EvidenceAnalyzer.analyze("candidate-0", ROUTE, NEARBY, ZONES, AREAS, COVERAGE);
        assertEquals(1, e.safety().cctv(), "같은 자리 CCTV 두 대는 한 곳, 550m 밖 CCTV는 제외");
        assertEquals(1, e.safety().emergencyBell());
        assertEquals(List.of("테스트초등학교"), e.schools());
        assertEquals(List.of("테스트초"), e.schoolZones());
        assertEquals(1, e.accidentAreas().size());
        assertEquals("보행 어린이 사고 다발 지역", e.accidentAreas().get(0).label());
    }

    @Test void picksKeyPointsFromPublicDataNotTmapCoordinates() {
        var e = EvidenceAnalyzer.analyze("candidate-0", ROUTE, NEARBY, ZONES, AREAS, COVERAGE);
        var first = e.keyPoints().get(0);
        assertEquals("CROSSWALK", first.kind());
        assertEquals("CROSSING", first.reason());
        assertEquals(37.59001, first.lat(), 1e-9, "지점 좌표는 TMAP 건넘 좌표가 아니라 공공자료 횡단보도 좌표");
        assertEquals("ESTIMATED", first.signalStatus());
        assertTrue(e.keyPoints().stream().noneMatch(p -> p.originalId().equals("id-2")), "건너지 않는 횡단보도는 안내 지점이 아니다");
        assertTrue(e.keyPoints().stream().anyMatch(p -> p.reason().equals("TURN") && p.kind().equals("CCTV")));
        for (int i = 1; i < e.keyPoints().size(); i++)
            assertTrue(e.keyPoints().get(i).alongM() >= e.keyPoints().get(i - 1).alongM(), "출발에서 가까운 순서");
    }

    @Test void reportsCoverageLimitsAndNeverAScore() throws Exception {
        var e = EvidenceAnalyzer.analyze("candidate-0", ROUTE, NEARBY, ZONES, AREAS, COVERAGE);
        assertEquals(List.of("중랑구"), e.coverage().districts());
        assertEquals(List.of("중랑구 보안등"), e.coverage().missingKinds(), "근처에 없는 구의 누락은 넣지 않는다");
        assertTrue(e.limitations().stream().anyMatch(s -> s.contains("추정") && s.contains("미확인")));
        String json = new ObjectMapper().writeValueAsString(e);
        assertFalse(json.toLowerCase().contains("score"));
        assertFalse(json.contains("가장 안전"));
    }

    @Test void emptyAreaSaysNoDataInsteadOfLookingSafe() {
        var e = EvidenceAnalyzer.analyze("candidate-0", ROUTE, List.of(), List.of(), List.of(), COVERAGE);
        assertEquals(2, e.crossings().unmatched());
        assertTrue(e.keyPoints().isEmpty());
        assertTrue(e.limitations().get(0).contains("적재된 공공자료가 없어요"));
    }
}
