package com.safeschoolroad.route.analysis;

import com.safeschoolroad.external.SafeMapClient;
import com.safeschoolroad.geo.LatLng;
import com.safeschoolroad.geo.Polyline;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;

/** 원본 WMS는 그대로 두고 공식 범례 색과 경로를 대조한다. 5m 간격 표본으로 길이를 근사한다. */
@Service
public class CrimeExposureService {
    private final SafeMapClient client;
    public CrimeExposureService(SafeMapClient client) { this.client = client; }

    public List<CrimeExposure> compare(List<List<LatLng>> routes) {
        try {
            List<LatLng> all = routes.stream().flatMap(List::stream).toList();
            double[] box = new Polyline(all).bounds(15);
            // 지도 한 픽셀이 10m보다 크면 좁은 도로를 구별할 수 없어서 숫자를 만들지 않는다.
            double resolution = Math.max(Polyline.distance(new LatLng(box[0],box[1]), new LatLng(box[2],box[1])),
                    Polyline.distance(new LatLng(box[0],box[1]), new LatLng(box[0],box[3]))) / 512;
            if (resolution > 10) return unknown(routes.size(), "경로 범위가 넓어 도로 등급을 구별하기 어려워요.");
            int[] palette = client.crimePalette(); // 범례가 바뀌거나 검증 실패하면 미확인
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(client.crimeMap(box[0],box[1],box[2],box[3])));
            return routes.stream().map(line -> measure(line, image, box, palette)).toList();
        } catch (Exception e) {
            return unknown(routes.size(), "범죄주의구간 정보를 확인하지 못했어요. 잠시 뒤 다시 찾아 주세요.");
        }
    }
    private static List<CrimeExposure> unknown(int count, String message) {
        return java.util.stream.IntStream.range(0,count).mapToObj(i -> CrimeExposure.unknown(message)).toList();
    }

    static CrimeExposure measure(List<LatLng> route, BufferedImage image, double[] box, int[] palette) {
        Polyline line = new Polyline(route);
        double length = line.length();
        if (length <= 0 || length > 20_000) return CrimeExposure.unknown("경로 길이를 확인하지 못했어요.");
        double[] meters = new double[10];
        double known = 0;
        int max = 0;
        var segments=new java.util.ArrayList<CrimeExposure.Segment>();
        String previous="";java.util.ArrayList<List<Double>> segmentLine=null;
        for (double at=0; at<length; at+=5) {
            double segment = Math.min(5, length-at);
            LatLng point = line.pointAt(at+segment/2);
            int x = (int)Math.floor((point.lng()-box[1])/(box[3]-box[1])*image.getWidth());
            int y = (int)Math.floor((box[2]-point.lat())/(box[2]-box[0])*image.getHeight());
            // 가장 가까운 픽셀만 사용: 옆 도로의 높은 등급을 끌어오지 않는다.
            int grade = x<0 || y<0 || x>=image.getWidth() || y>=image.getHeight()?0:grade(image.getRGB(x,y), palette);
            boolean inImage=x>=0 && y>=0 && x<image.getWidth() && y<image.getHeight();
            boolean noDisplay=inImage && (image.getRGB(x,y)>>>24)==0;
            String band=grade==0?(noDisplay?"NO_DISPLAY":"UNKNOWN"):grade>=7?"UPPER":"OBSERVED";
            if(!band.equals(previous)){
                segmentLine=new java.util.ArrayList<>();var start=line.pointAt(at);segmentLine.add(List.of(start.lat(),start.lng()));
                segments.add(new CrimeExposure.Segment(band,segmentLine));previous=band;
            }
            var finish=line.pointAt(at+segment);segmentLine.add(List.of(finish.lat(),finish.lng()));
            if (grade>0) { meters[grade-1]+=segment; known+=segment; max=Math.max(max,grade); }
        }
        List<Long> byGrade = Arrays.stream(meters).mapToObj(Math::round).toList();
        long upper = Math.round(Arrays.stream(meters,6,10).sum());
        int coverage = (int)Math.round(known/length*100);
        return new CrimeExposure(known>0, Math.round(known), Math.round(length-known), upper, coverage, max, byGrade,
                known>0 ? "공식 1~10등급 지도와 5m 간격으로 대조한 근사값이에요. 색이 없는 구간과 판독 실패를 구분해요."
                        : "공식 지도에서 대응하는 등급 표시를 찾지 못했어요. 영역 밖인지 누락인지는 이 응답만으로 확정할 수 없어요.",List.copyOf(segments));
    }
    static int grade(int argb, int[] palette) {
        if ((argb>>>24)<145) return 0; // 투명·흐릿한 경계 픽셀은 미확인
        int nearest = 0, distance = Integer.MAX_VALUE;
        for (int i=0;i<palette.length;i++) {
            int rgb=palette[i];
            int d=Math.max(Math.abs((argb>>16&255)-(rgb>>16&255)),
                    Math.max(Math.abs((argb>>8&255)-(rgb>>8&255)), Math.abs((argb&255)-(rgb&255))));
            if(d<distance) {distance=d;nearest=i+1;}
        }
        return distance<=8 ? nearest : 0;
    }
}
