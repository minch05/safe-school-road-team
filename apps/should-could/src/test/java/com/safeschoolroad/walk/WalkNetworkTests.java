package com.safeschoolroad.walk;

import com.safeschoolroad.external.TmapRoute;
import com.safeschoolroad.geo.LatLng;
import com.safeschoolroad.guide.GuidePoint;
import com.safeschoolroad.guide.GuideValidator;
import com.safeschoolroad.guide.TemplateGuide;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WalkNetworkTests {
    private final LatLng center=new LatLng(37.57,126.98);
    private WalkNetwork.Junction junction(String id,double lat,double lng) {
        return new WalkNetwork.Junction(id,lat,lng,List.of(new LatLng(lat-.0003,lng),new LatLng(lat,lng+.0004),new LatLng(lat,lng-.0004)));
    }
    private TmapRoute route(boolean right) {
        return new TmapRoute(List.of(new LatLng(37.5697,126.98),center,new LatLng(37.57,right?126.9804:126.9796)),
                List.of(new TmapRoute.Turn(center.lat(),center.lng(),right?13:12,"사용하지 않는 외부 설명")),70,60);
    }
    @Test void computesDirectionFromPublicBranchesAndUsesOriginalCoordinates() {
        var network=new WalkNetwork(List.of(junction("1",center.lat(),center.lng())));
        for(boolean right:List.of(true,false)) {
            var points=network.match(route(right)); assertEquals(1,points.size());
            var point=points.get(0); assertEquals(right?"RIGHT":"LEFT",point.turnDirection());
            assertEquals("SEOUL_WALK_NODE",point.facility().source());
            assertEquals(center.lat(),point.facility().lat());
            var guide=new GuidePoint(1,point.facility().kind(),"갈림길",null,point.reason(),true,point.turnDirection());
            String text=TemplateGuide.sentence(guide);
            assertTrue(text.contains(right?"오른쪽":"왼쪽"));
            assertEquals(List.of(),GuideValidator.validate(List.of(guide),Map.of(1,text)));
            assertFalse(GuideValidator.validate(List.of(guide),Map.of(1,text.replace(right?"오른쪽":"왼쪽",right?"왼쪽":"오른쪽"))).isEmpty());
        }
    }
    @Test void rejectsTwoEquallyCloseJunctions() {
        var network=new WalkNetwork(List.of(junction("1",37.57,126.98),junction("2",37.57001,126.98)));
        assertTrue(network.match(route(true)).isEmpty());
    }
    @Test void rejectsOffRouteAndAmbiguousBranches() {
        assertTrue(new WalkNetwork(List.of(junction("1",37.571,126.98))).match(route(true)).isEmpty());
        var n=junction("1",37.57,126.98);
        var ambiguous=new WalkNetwork.Junction(n.id(),n.lat(),n.lng(),List.of(n.branches().get(0),n.branches().get(1),new LatLng(37.57001,126.9804)));
        assertTrue(new WalkNetwork(List.of(ambiguous)).match(route(true)).isEmpty());
    }
    @Test void analyzerAddsJunctionWhenNoFacilityExists() {
        var network=new WalkNetwork(List.of(junction("1",37.57,126.98)));
        var analyzer=new com.safeschoolroad.route.analysis.RouteAnalyzer(
                org.mockito.Mockito.mock(com.safeschoolroad.facility.FacilityRepository.class),
                org.mockito.Mockito.mock(com.safeschoolroad.facility.RiskAreaRepository.class),new com.fasterxml.jackson.databind.ObjectMapper());
        org.springframework.test.util.ReflectionTestUtils.setField(analyzer,"walkNetwork",network);
        var result=analyzer.analyze(route(true));
        assertEquals(1,result.keyPoints().size()); assertEquals(0,result.turnsWithoutFacility());
        assertEquals("RIGHT",result.keyPoints().get(0).turnDirection());
        assertTrue(result.mapFeatures().stream().anyMatch(f->f.source().contains("공공누리")));
    }

    @Test void downloadedPublicGraphLoadsAndProducesSampleGuidance() throws Exception {
        var path=java.nio.file.Path.of("무제 폴더/도보네트워크/walk-network.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(path));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(path)));
        assertThrows(IllegalArgumentException.class,()->new WalkNetwork(json,path.toString(),"wrong"));
        var network=new WalkNetwork(json,path.toString(),hash);
        var root=json.readTree(path.toFile()); var examples=new java.util.ArrayList<Map<String,Object>>();
        // 경로 API가 아닌 공개 도보 링크로 구성한 검사선. 실보행 정확성 시험과 구분한다.
        for(var n:root.path("junctions")) {
            if(examples.size()>=3)break;
            var c=new LatLng(n.path("lat").asDouble(),n.path("lng").asDouble());
            var branches=n.path("branches");
            for(int i=0;i<branches.size()&&examples.size()<3;i++)for(int j=i+1;j<branches.size()&&examples.size()<3;j++) {
                var a=new LatLng(branches.get(i).path("lat").asDouble(),branches.get(i).path("lng").asDouble());
                var b=new LatLng(branches.get(j).path("lat").asDouble(),branches.get(j).path("lng").asDouble());
                var r=new TmapRoute(List.of(a,c,b),List.of(new TmapRoute.Turn(c.lat(),c.lng(),12,"")),0,0);
                var matched=network.match(r);
                if(matched.isEmpty())continue;
                var p=matched.get(0);
                var guide=new GuidePoint(1,p.facility().kind(),"갈림길",null,p.reason(),false,p.turnDirection());
                examples.add(Map.of("publicNodeId",p.facility().sourceId(),"text",TemplateGuide.sentence(guide),"source","서울특별시 도보망, 공공누리 1유형, 2020년 기준"));
            }
        }
        assertEquals(3,examples.size());
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("build"));
        json.writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of("build/walk-network-demo.json").toFile(),examples);
    }

    @Test void ignoresUnverifiedTmapDirectionAndEmptyNetwork() {
        var r=route(true);
        var oppositeLabel=new TmapRoute(r.line(),List.of(new TmapRoute.Turn(center.lat(),center.lng(),12,"왼쪽")),70,60);
        assertEquals("RIGHT",new WalkNetwork(List.of(junction("1",37.57,126.98))).match(oppositeLabel).get(0).turnDirection());
        assertTrue(new WalkNetwork(List.of()).match(r).isEmpty());
    }
}
