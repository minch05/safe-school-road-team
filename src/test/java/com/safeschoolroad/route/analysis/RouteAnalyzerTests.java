package com.safeschoolroad.route.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.external.TmapRoute;
import com.safeschoolroad.facility.Facility;
import com.safeschoolroad.facility.FacilityKind;
import com.safeschoolroad.facility.RiskAreaRepository.RiskArea;
import com.safeschoolroad.facility.SignalStatus;
import com.safeschoolroad.geo.LatLng;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 핵심 지점 고르기 규칙 (HANDOFF 5-3). 동쪽으로 곧게 600m 가는 가짜 경로로 시험한다. DB 없이 돈다.
class RouteAnalyzerTests {

    // 서울 위도에서 경도 1도 ≈ 88,200m → 1m ≈ 0.00001134도
    private static final double LAT = 37.59, LNG0 = 127.08, PER_M = 1 / 88_200.0, LAT_PER_M = 1 / 110_540.0;

    private final RouteAnalyzer analyzer = new RouteAnalyzer(null, null, new ObjectMapper());
    private long ids = 1;

    private static LatLng at(double eastM, double northM) {
        return new LatLng(LAT + northM * LAT_PER_M, LNG0 + eastM * PER_M);
    }

    private Facility facility(FacilityKind kind, double eastM, double northM, SignalStatus signal) {
        LatLng p = at(eastM, northM);
        long id = ids++;
        return new Facility(id, kind, kind.getLabel() + id, p.lat(), p.lng(), signal, "TEST", String.valueOf(id));
    }

    private static TmapRoute route(List<TmapRoute.Turn> turns) {
        return new TmapRoute(List.of(at(0, 0), at(600, 0)), turns, 600, 480);
    }

    private static TmapRoute.Turn turn(double eastM, int type) {
        LatLng p = at(eastM, 0);
        return new TmapRoute.Turn(p.lat(), p.lng(), type, "");
    }

    @Test
    void 건너는_횡단보도는_반드시_넣고_건너지_않는_횡단보도는_넣지_않는다() {
        Facility crossed = facility(FacilityKind.CROSSWALK, 100, 5, SignalStatus.ESTIMATED);
        Facility sideCrosswalk = facility(FacilityKind.CROSSWALK, 300, 10, SignalStatus.UNKNOWN); // TMAP이 건넌다고 하지 않음
        Facility light = facility(FacilityKind.PEDESTRIAN_SIGNAL, 103, 5, null); // 신호 추정 근거
        RouteAnalysis a = analyzer.analyze(route(List.of(turn(100, 211))), List.of(crossed, sideCrosswalk, light), List.of(), List.of());

        assertThat(a.keyPoints()).extracting(k -> k.facility().id()).contains(crossed.id()).doesNotContain(sideCrosswalk.id());
        assertThat(a.crossingCount()).isEqualTo(1);
        assertThat(a.crossingsEstimated()).isEqualTo(1);
        assertThat(a.keyPoints().get(0).reason()).isEqualTo(PointReason.CROSSING);
    }

    @Test
    void 데이터에_없는_건넘은_미확인으로_센다() {
        RouteAnalysis a = analyzer.analyze(route(List.of(turn(100, 211))), List.of(), List.of(), List.of());
        assertThat(a.mapFeatures()).hasSize(1);
        assertThat(a.mapFeatures().get(0).kind()).isEqualTo("CROSSWALK");
        assertThat(a.mapFeatures().get(0).detail()).contains("신호 미확인");
        assertThat(a.mapFeatures().get(0).lng()).isEqualTo(at(100,0).lng());
        assertThat(a.crossingCount()).isEqualTo(1);
        assertThat(a.crossingsUnknown()).isEqualTo(1);
    }

    @Test void 가까운_보행등을_표시해도_건널목_신호로_확정하지_않는다() {
        var signal=facility(FacilityKind.PEDESTRIAN_SIGNAL,100,2,null);
        var light=facility(FacilityKind.SECURITY_LIGHT,200,2,null);
        var a=analyzer.analyze(route(List.of(turn(100,211))),List.of(signal,light),List.of(),List.of());
        assertThat(a.crossingsUnknown()).isEqualTo(1);
        assertThat(a.crossingsEstimated()).isZero();
        assertThat(a.safetyFacilities()).isZero();
        assertThat(a.keyPoints()).isEmpty();
        assertThat(a.mapFeatures()).extracting(RouteAnalysis.MapFeature::kind).containsExactly("CROSSWALK","SIGNAL","LIGHT");
        assertThat(a.mapFeatures().get(1).detail()).contains("확정하지 않아요");
    }

    // 보행등은 가장 가까운 횡단보도 하나에만 연결한다 (SignalLinks). 원본이 "추정"이어도 보행등이 옆 횡단보도에 더 가까우면 미확인
    @Test void 다른_횡단보도에_더_가까운_보행등은_연결하지_않는다() {
        var crossing=facility(FacilityKind.CROSSWALK,100,0,SignalStatus.ESTIMATED);
        var other=facility(FacilityKind.CROSSWALK,105,0,SignalStatus.UNKNOWN);
        var signal=facility(FacilityKind.PEDESTRIAN_SIGNAL,104,0,null);
        var a=analyzer.analyze(route(List.of(turn(100,211))),List.of(crossing,other,signal),List.of(),List.of());
        assertThat(a.crossingsEstimated()).isZero();
        assertThat(a.crossingsUnknown()).isEqualTo(1);
        assertThat(a.keyPoints().get(0).facility().signalStatus()).isEqualTo(SignalStatus.UNKNOWN);
        assertThat(a.mapFeatures().get(0).verification()).isEqualTo("AMBIGUOUS_SIGNAL_LINK");
    }
    @Test void 원본이_미확인이어도_가장_가까운_보행등이_있으면_추정() {
        var crossing=facility(FacilityKind.CROSSWALK,100,0,SignalStatus.UNKNOWN);
        var signal=facility(FacilityKind.PEDESTRIAN_SIGNAL,112,0,null); // 12m: 예전 10m 규칙으로는 미확인
        var a=analyzer.analyze(route(List.of(turn(100,211))),List.of(crossing,signal),List.of(),List.of());
        assertThat(a.crossingsEstimated()).isEqualTo(1);
        assertThat(a.keyPoints().get(0).facility().signalStatus()).isEqualTo(SignalStatus.ESTIMATED);
    }
    @Test void 단독_연결_후보가_추가로_있으면_추정을_유지한다() {
        var crossing=facility(FacilityKind.CROSSWALK,100,0,SignalStatus.ESTIMATED);
        var other=facility(FacilityKind.CROSSWALK,108,0,SignalStatus.UNKNOWN);
        var shared=facility(FacilityKind.PEDESTRIAN_SIGNAL,104,0,null);
        var unique=facility(FacilityKind.PEDESTRIAN_SIGNAL,93,0,null);
        var a=analyzer.analyze(route(List.of(turn(100,211))),List.of(crossing,other,shared,unique),List.of(),List.of());
        assertThat(a.crossingsEstimated()).isEqualTo(1);
    }

    @Test
    void 꺾는_곳은_우선순위가_높은_시설_하나() {
        Facility cctv = facility(FacilityKind.CCTV, 200, 5, null);
        Facility bell = facility(FacilityKind.EMERGENCY_BELL, 205, 8, null);
        Facility school = facility(FacilityKind.SCHOOL, 210, 15, null);
        RouteAnalysis a = analyzer.analyze(route(List.of(turn(200, 12))), List.of(cctv, bell, school), List.of(), List.of());

        List<RouteAnalysis.KeyPoint> turnPoints = a.keyPoints().stream().filter(k -> k.reason() == PointReason.TURN).toList();
        assertThat(turnPoints).hasSize(1);
        assertThat(turnPoints.get(0).facility().kind()).isEqualTo(FacilityKind.SCHOOL); // 학교 > 비상벨 > CCTV
    }

    @Test
    void 꺾는_곳_근처에_시설이_없으면_센다() {
        RouteAnalysis a = analyzer.analyze(route(List.of(turn(300, 13))), List.of(), List.of(), List.of());
        assertThat(a.turnsWithoutFacility()).isEqualTo(1);
    }

    @Test
    void 지점_사이가_200m를_넘으면_가운데에_시설을_채운다() {
        // 600m 동안 지점이 없다 → 길에서 25m 안 시설로 채운다. 40m 떨어진 시설은 쓰지 않는다
        List<Facility> fillers = new ArrayList<>();
        for (int m = 50; m <= 550; m += 50) {
            fillers.add(facility(FacilityKind.CCTV, m, 10, null));
        }
        Facility far = facility(FacilityKind.EMERGENCY_BELL, 300, 40, null);
        fillers.add(far);
        RouteAnalysis a = analyzer.analyze(route(List.of()), fillers, List.of(), List.of());

        assertThat(a.keyPoints()).isNotEmpty().allMatch(k -> k.reason() == PointReason.GAP);
        assertThat(a.keyPoints()).extracting(k -> k.facility().id()).doesNotContain(far.id());
        assertThat(a.maxGapMeters()).isLessThanOrEqualTo(200);
    }

    @Test
    void 같은_자리_30m_안에는_하나만_남기고_건너는_횡단보도가_이긴다() {
        Facility crossed = facility(FacilityKind.CROSSWALK, 300, 3, SignalStatus.UNKNOWN);
        Facility bell = facility(FacilityKind.EMERGENCY_BELL, 310, 5, null);
        RouteAnalysis a = analyzer.analyze(route(List.of(turn(300, 211), turn(310, 13))), List.of(crossed, bell), List.of(), List.of());

        assertThat(a.keyPoints()).hasSize(1);
        assertThat(a.keyPoints().get(0).facility().id()).isEqualTo(crossed.id());
    }

    @Test
    void 안전시설은_같은_종류_20m_안을_하나로_센다() {
        Facility c1 = facility(FacilityKind.CCTV, 100, 5, null);
        Facility c2 = facility(FacilityKind.CCTV, 105, 5, null);   // 같은 기둥
        Facility c3 = facility(FacilityKind.CCTV, 200, 5, null);
        Facility b1 = facility(FacilityKind.EMERGENCY_BELL, 100, 5, null);
        RouteAnalysis a = analyzer.analyze(route(List.of()), List.of(c1, c2, c3, b1), List.of(), List.of());
        assertThat(a.safetyFacilities()).isEqualTo(3);
    }

    @Test
    void 어린이보호구역은_300m_안만() {
        Facility near = facility(FacilityKind.SCHOOL_ZONE, 300, 250, null);
        Facility far = facility(FacilityKind.SCHOOL_ZONE, 300, 400, null);
        RouteAnalysis a = analyzer.analyze(route(List.of()), List.of(), List.of(near, far), List.of());
        assertThat(a.schoolZones()).containsExactly(near.name());
    }

    @Test
    void 사고다발지역을_지나가는지() {
        LatLng sw = at(250, -20), ne = at(350, 20);
        String square = "{\"type\":\"Polygon\",\"coordinates\":[[[%f,%f],[%f,%f],[%f,%f],[%f,%f],[%f,%f]]]}".formatted(
                sw.lng(), sw.lat(), ne.lng(), sw.lat(), ne.lng(), ne.lat(), sw.lng(), ne.lat(), sw.lng(), sw.lat());
        LatLng sw2 = at(250, 100), ne2 = at(350, 150);
        String away = "{\"type\":\"Polygon\",\"coordinates\":[[[%f,%f],[%f,%f],[%f,%f],[%f,%f],[%f,%f]]]}".formatted(
                sw2.lng(), sw2.lat(), ne2.lng(), sw2.lat(), ne2.lng(), ne2.lat(), sw2.lng(), ne2.lat(), sw2.lng(), sw2.lat());
        RouteAnalysis a = analyzer.analyze(route(List.of()), List.of(), List.of(),
                List.of(new RiskArea(1, "K", "지나는 곳", square), new RiskArea(2, "K", "옆 동네", away)));
        assertThat(a.accidentAreas()).containsExactly("지나는 곳");
    }
    @Test
    void 지킴이집을_비상벨보다_우선하고_보안등은_핵심지점에서_제외한다() {
        Facility house = facility(FacilityKind.CHILD_SAFETY_HOUSE, 200, 10, null);
        Facility bell = facility(FacilityKind.EMERGENCY_BELL, 200, 5, null);
        Facility light = facility(FacilityKind.SECURITY_LIGHT, 200, 0, null);
        RouteAnalysis result = analyzer.analyze(route(List.of(turn(200, 12))), List.of(house, bell, light), List.of(), List.of());
        assertThat(result.keyPoints()).extracting(k -> k.facility().id()).contains(house.id()).doesNotContain(light.id());
        assertThat(result.keyPoints().stream().filter(k -> k.reason() == PointReason.TURN).findFirst().orElseThrow().facility().id()).isEqualTo(house.id());
    }
    @Test void 비교에_센_보호구역_사고영역_안전시설의_위치를_같이_반환한다(){
        var cctv=facility(FacilityKind.CCTV,200,5,null);
        var duplicate=facility(FacilityKind.CCTV,205,5,null);
        var zone=facility(FacilityKind.SCHOOL_ZONE,100,250,null);
        String polygon="{\"type\":\"Polygon\",\"coordinates\":[[[127.0808,37.5899],[127.0815,37.5899],[127.0815,37.5901],[127.0808,37.5901],[127.0808,37.5899]]]}";
        var area=new RiskArea(42,"CHILD","검수 사고 영역",polygon,2023);
        var result=analyzer.analyze(route(List.of()),List.of(cctv,duplicate),List.of(zone),List.of(area));
        assertThat(result.safetyFacilities()).isEqualTo(1);
        assertThat(result.mapFeatures().stream().filter(f->f.kind().equals("SAFETY"))).hasSize(1);
        assertThat(result.mapFeatures().stream().filter(f->f.kind().equals("SCHOOL_ZONE"))).hasSize(result.schoolZones().size());
        assertThat(result.accidentAreas()).containsExactly("검수 사고 영역");
        assertThat(result.mapFeatures().stream().filter(f->f.kind().equals("ACCIDENT"))).singleElement().satisfies(f->{assertThat(f.geometry()).isEqualTo(polygon);assertThat(f.detail()).contains("2023");});
    }

    @Test void 신호_미확인의_원인을_구분하고_주변_보행등을_확정으로_쓰지_않는다(){
        var crossing=facility(FacilityKind.CROSSWALK,100,1,SignalStatus.UNKNOWN);
        var signal=facility(FacilityKind.PEDESTRIAN_SIGNAL,100,21,null); // 20m: 연결 거리(15m) 밖, 건넘 위치 30m 안
        var matched=analyzer.analyze(route(List.of(turn(100,211))),List.of(crossing,signal),List.of(),List.of());
        assertThat(matched.crossingsUnknown()).isEqualTo(1);
        assertThat(matched.mapFeatures().stream().filter(f->f.kind().equals("CROSSWALK"))).singleElement().satisfies(f->assertThat(f.verification()).isEqualTo("UNLINKED_NEARBY_SIGNAL"));
        var absent=analyzer.analyze(route(List.of(turn(100,211))),List.of(crossing),List.of(),List.of());
        assertThat(absent.mapFeatures().get(0).verification()).isEqualTo("NO_NEARBY_SIGNAL");
        var unmatched=analyzer.analyze(route(List.of(turn(100,211))),List.of(signal),List.of(),List.of());
        assertThat(unmatched.mapFeatures().get(0).verification()).isEqualTo("UNMATCHED_CROSSWALK");
    }
}
