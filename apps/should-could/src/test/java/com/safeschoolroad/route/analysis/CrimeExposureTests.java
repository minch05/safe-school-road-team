package com.safeschoolroad.route.analysis;

import com.safeschoolroad.geo.LatLng;
import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CrimeExposureTests {
    final int[] colors={0xffffb2,0xfee88b,0xfed165,0xfdb751,0xfd9b43,0xfa7a35,0xf45629,0xea3420,0xd31a23,0xbd0026};
    @Test void 등급별_길이와_미확인을_분리하고_겹쳐_세지_않는다() {
        var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB);
        for(int x=0;x<256;x++) for(int y=0;y<512;y++) image.setRGB(x,y,0xff000000|colors[7]);
        var result=CrimeExposureService.measure(List.of(new LatLng(37.59,127.08),new LatLng(37.59,127.081)),image,
                new double[]{37.589,127.08,37.591,127.081},colors);
        assertThat(result.available()).isTrue();
        assertThat(result.coveragePercent()).isBetween(45,55);
        assertThat(result.upperGradeMeters()).isEqualTo(result.observedMeters());
        assertThat(result.unknownMeters()).isGreaterThan(30);
        assertThat(result.maxGrade()).isEqualTo(8);
        assertThat(result.segments()).extracting(CrimeExposure.Segment::band).containsExactly("UPPER","NO_DISPLAY");
        assertThat(result.segments().get(0).line().get(0)).containsExactly(37.59,127.08);
        assertThat(result.segments().get(1).line().get(result.segments().get(1).line().size()-1)).containsExactly(37.59,127.081);
    }
    @Test void 투명_경계_알수없는_색은_안전한_등급으로_해석하지_않는다() {
        assertThat(CrimeExposureService.grade(0x00ffffb2,colors)).isZero();
        assertThat(CrimeExposureService.grade(0xff123456,colors)).isZero();
        assertThat(CrimeExposureService.grade(0x80bd0026,colors)).isZero();
        assertThat(CrimeExposureService.grade(0xffbd0026,colors)).isEqualTo(10);
        assertThat(CrimeExposureService.grade(0x99bd0026,colors)).isEqualTo(10);
    }
    @Test void 빈_지도는_미확인이며_1등급은_확인된_등급으로_센다() {
        var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB);
        var line=List.of(new LatLng(37.59,127.08),new LatLng(37.59,127.081));
        double[] box={37.589,127.079,37.591,127.082};
        var blank=CrimeExposureService.measure(line,image,box,colors);
        assertThat(blank.available()).isFalse();
        assertThat(blank.segments()).extracting(CrimeExposure.Segment::band).containsExactly("NO_DISPLAY");
        image.setRGB(256,256,0xff123456);
        for(int x=0;x<512;x++) for(int y=0;y<512;y++) image.setRGB(x,y,0xff000000|colors[0]);
        var result=CrimeExposureService.measure(line,image,box,colors);
        assertThat(result.coveragePercent()).isEqualTo(100);
        assertThat(result.upperGradeMeters()).isZero();
        assertThat(result.maxGrade()).isEqualTo(1);
    }
}
