package com.safeschoolroad.external;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SafeMapClientTests {
    @Test void 범례를_검증하고_색_순서를_등급순으로_정렬하고_캐시한다() {
        var builder=RestClient.builder();
        var server=MockRestServiceServer.bindTo(builder).build();
        var client=new SafeMapClient(builder.build(),"test-key");
        var items=new java.util.ArrayList<java.util.Map<String,Object>>();
        for(int grade=10;grade>=1;grade--) items.add(java.util.Map.of("LGD_NO",grade,"LGD_NM",grade+"등급","IMAGE",String.format("#%06x",grade),"STYLE","A2SM_CrmnlHspot_Tot_Tot"));
        try {
            var json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("header",java.util.Map.of("resultCode","00"),"body",java.util.Map.of("items",java.util.Map.of("item",items))));
            server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/openapi2/lgdInfo")).andRespond(withSuccess(json,MediaType.APPLICATION_JSON));
            int[] colors=client.crimePalette();
            assertThat(colors).containsExactly(1,2,3,4,5,6,7,8,9,10);
            colors[0]=999;
            assertThat(client.crimePalette()[0]).isEqualTo(1);
            server.verify();
        } catch (Exception e) { throw new AssertionError(e); }
    }
    @Test void 범례가_빠지거나_형식이_바뀌면_숫자를_만들지_않는다() {
        var builder=RestClient.builder();
        var server=MockRestServiceServer.bindTo(builder).build();
        var client=new SafeMapClient(builder.build(),"private-test-key");
        server.expect(request -> {}).andRespond(withSuccess("{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":[]}}}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(client::crimePalette).isInstanceOf(SafeMapClient.Unavailable.class).hasMessageNotContaining("private-test-key");
        server.verify();
    }
    private byte[] png() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB), "png", output);
        return output.toByteArray();
    }

    @Test void 공식_URL과_경도위도_순서를_사용하고_PNG만_반환한다() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new SafeMapClient(builder.build(), "test+key/=");
        byte[] image = png();
        server.expect(request -> {
            var uri = request.getURI();
            assertThat(uri.getHost()).isEqualTo("www.safemap.go.kr");
            assertThat(uri.getRawQuery()).contains("serviceKey=test%2Bkey%2F%3D")
                    .contains("bbox=127.080000%2C37.590000%2C127.090000%2C37.600000");
        })
                .andRespond(withSuccess(image, MediaType.IMAGE_PNG));
        assertThat(client.crimeMap(37.59, 127.08, 37.60, 127.09)).isEqualTo(image);
        server.verify();
    }

    @Test void HTML_오류를_지도나_안전한_구간으로_취급하지_않는다() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new SafeMapClient(builder.build(), "private-test-key");
        server.expect(request -> {}).andRespond(withSuccess("<html>error</html>", MediaType.TEXT_HTML));
        assertThatThrownBy(() -> client.crimeMap(37.59,127.08,37.60,127.09))
                .isInstanceOf(SafeMapClient.Unavailable.class).hasMessageContaining("미확인")
                .hasMessageNotContaining("private-test-key");
        // 실패 직후 중복 호출은 외부 서버로 가지 않는다.
        assertThatThrownBy(() -> client.crimeMap(37.59,127.08,37.60,127.09)).isInstanceOf(SafeMapClient.Unavailable.class);
        server.verify();
    }

    @Test void 키_없음_역전된_범위_NaN_과도한_범위를_차단한다() {
        var client = new SafeMapClient(RestClient.create(), "");
        assertThat(client.configured()).isFalse();
        assertThatThrownBy(() -> client.crimeMap(37.59,127.08,37.60,127.09)).isInstanceOf(SafeMapClient.Unavailable.class);
        assertThatThrownBy(() -> client.crimeMap(37.60,127.08,37.59,127.09)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.crimeMap(Double.NaN,127.08,37.60,127.09)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.crimeMap(37.40,126.75,37.73,127.20)).isInstanceOf(IllegalArgumentException.class);
    }
}
