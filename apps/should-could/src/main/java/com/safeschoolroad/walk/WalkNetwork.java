package com.safeschoolroad.walk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.external.TmapRoute;
import com.safeschoolroad.facility.Facility;
import com.safeschoolroad.facility.FacilityKind;
import com.safeschoolroad.geo.LatLng;
import com.safeschoolroad.geo.Polyline;
import com.safeschoolroad.route.analysis.PointReason;
import com.safeschoolroad.route.analysis.RouteAnalysis.KeyPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** 공공 원본의 연결 방향만으로 갈림길 안내를 만든다. TMAP 좌표/설명은 저장하지 않는다. */
@Component
public class WalkNetwork {
    public record Junction(String id, double lat, double lng, List<LatLng> branches) {}
    private final List<Junction> junctions;
    private record Cached(String sha256, List<Junction> junctions) {}
    private static volatile Cached cached;

    @org.springframework.beans.factory.annotation.Autowired
    public WalkNetwork(ObjectMapper json,
                       @Value("${app.walk-network.file:}") String file,
                       @Value("${app.walk-network.sha256:}") String sha256) throws Exception {
        if (file.isBlank()) { junctions=List.of(); return; }
        byte[] bytes=Files.readAllBytes(Path.of(file));
        if (sha256.isBlank() || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(sha256))
            throw new IllegalArgumentException("도보 네트워크 검토 파일의 SHA-256이 일치하지 않습니다");
        Cached existing=cached;
        if (existing != null && existing.sha256.equals(sha256)) { junctions=existing.junctions; return; }
        JsonNode root=json.readTree(bytes);
        if (!"KOGL-1".equals(root.path("license").asText()) || root.path("surveyYear").asInt()!=2020
                || !"https://data.seoul.go.kr/dataList/OA-21208/S/1/datasetView.do".equals(root.path("sourceUrl").asText()))
            throw new IllegalArgumentException("도보 네트워크 출처/이용 조건/조사연도를 확인해 주세요");
        List<Junction> data=new ArrayList<>(); Set<String> ids=new HashSet<>();
        for (JsonNode n:root.path("junctions")) {
            String id=n.path("id").asText();
            if (!id.matches("[0-9]{1,30}") || !ids.add(id)) throw new IllegalArgumentException("잘못된 노드 ID");
            LatLng p=coordinate(n); List<LatLng> branches=new ArrayList<>();
            for (JsonNode b:n.path("branches")) branches.add(coordinate(b));
            if (branches.size()<3) throw new IllegalArgumentException("갈림길 연결 부족");
            data.add(new Junction(id,p.lat(),p.lng(),List.copyOf(branches)));
        }
        if (data.isEmpty()) throw new IllegalArgumentException("도보 네트워크가 비어 있습니다");
        junctions=List.copyOf(data);
        cached=new Cached(sha256,junctions);
    }

    WalkNetwork(List<Junction> junctions) { this.junctions=List.copyOf(junctions); }

    private static LatLng coordinate(JsonNode n) {
        double lat=n.path("lat").asDouble(Double.NaN), lng=n.path("lng").asDouble(Double.NaN);
        if (!(lat>=37.4 && lat<=37.8 && lng>=126.7 && lng<=127.3)) throw new IllegalArgumentException("서울 범위 밖 좌표");
        return new LatLng(lat,lng);
    }

    /** 실제로 꺾는 곳만 대조. 여러 노드/갈래가 비슷하게 맞으면 안내를 만들지 않는다. */
    public List<KeyPoint> match(TmapRoute route) {
        if (junctions.isEmpty()) return List.of();
        Polyline line=new Polyline(route.line()); double[] box=line.bounds(15);
        List<Junction> nearby=junctions.stream().filter(n->n.lat>=box[0]&&n.lat<=box[2]&&n.lng>=box[1]&&n.lng<=box[3]).toList();
        Map<String,KeyPoint> out=new LinkedHashMap<>();
        for (var turn:route.turns()) {
            if (!turn.isTurn()) continue;
            LatLng t=new LatLng(turn.lat(),turn.lng());
            List<Junction> candidates=nearby.stream().filter(n->Polyline.distance(t,point(n))<=12)
                    .filter(n->line.locate(n.lat,n.lng).distance()<=8)
                    .sorted(Comparator.comparingDouble(n->Polyline.distance(t,point(n)))).toList();
            if (candidates.isEmpty() || (candidates.size()>1 && Polyline.distance(t,point(candidates.get(1)))-Polyline.distance(t,point(candidates.get(0)))<5)) continue;
            Junction n=candidates.get(0); var loc=line.locate(n.lat,n.lng);
            if (loc.along()<15 || line.length()-loc.along()<15) continue;
            double before=bearing(point(n),line.pointAt(loc.along()-12));
            double after=bearing(point(n),line.pointAt(loc.along()+12));
            int in=branch(n,before), exit=branch(n,after);
            if (in<0 || exit<0 || in==exit) continue;
            double angle=wrap(bearing(point(n),n.branches.get(exit))-bearing(point(n),n.branches.get(in))-180);
            if (Math.abs(angle)<35 || Math.abs(angle)>135) continue;
            // 북쪽 기준 시계 방향 각도. 공개 링크의 접속 방향으로만 계산한다.
            String direction=angle>0?"RIGHT":"LEFT";
            Facility f=new Facility(-Long.parseLong(n.id),FacilityKind.WALK_JUNCTION,"갈림길",n.lat,n.lng,null,"SEOUL_WALK_NODE",n.id,null);
            out.put(n.id,new KeyPoint(f,PointReason.TURN,loc.along(),loc.distance(),direction));
        }
        return List.copyOf(out.values());
    }

    private static LatLng point(Junction n) { return new LatLng(n.lat,n.lng); }
    private static double bearing(LatLng a,LatLng b) { return Math.toDegrees(Math.atan2((b.lng()-a.lng())*Math.cos(Math.toRadians(a.lat())),b.lat()-a.lat())); }
    private static double wrap(double a) { while(a>180)a-=360; while(a<=-180)a+=360; return a; }
    private static int branch(Junction n,double target) {
        int best=-1; double first=Double.MAX_VALUE,second=Double.MAX_VALUE;
        for(int i=0;i<n.branches.size();i++) {
            double delta=Math.abs(wrap(bearing(point(n),n.branches.get(i))-target));
            if(delta<first){second=first;first=delta;best=i;} else second=Math.min(second,delta);
        }
        return first<=25 && second-first>=15?best:-1;
    }
}
