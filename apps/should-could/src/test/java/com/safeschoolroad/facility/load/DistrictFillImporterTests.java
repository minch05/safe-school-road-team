package com.safeschoolroad.facility.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// 네이버 Reverse Geocoding 응답 읽기
class DistrictFillImporterTests {

    private final ObjectMapper json = new ObjectMapper();

    private String parse(String body) throws Exception {
        return DistrictFillImporter.parse(json.readTree(body));
    }

    @Test
    void 서울이면_구_이름() throws Exception {
        assertThat(parse("{\"status\":{\"name\":\"ok\"},\"results\":[{\"region\":{\"area1\":{\"name\":\"서울특별시\"},\"area2\":{\"name\":\"중랑구\"}}}]}"))
                .isEqualTo("중랑구");
    }

    @Test
    void 서울_밖이면_빈_글자() throws Exception {
        assertThat(parse("{\"status\":{\"name\":\"ok\"},\"results\":[{\"region\":{\"area1\":{\"name\":\"경기도\"},\"area2\":{\"name\":\"과천시\"}}}]}"))
                .isEmpty();
    }

    @Test
    void 결과가_없거나_오류면_판정하지_않는다() throws Exception {
        assertThat(parse("{\"status\":{\"name\":\"no results\"},\"results\":[]}")).isNull();
        assertThat(parse("{\"status\":{\"name\":\"ok\"},\"results\":[{\"region\":{\"area1\":{\"name\":\"서울특별시\"},\"area2\":{\"name\":\"\"}}}]}")).isNull();
    }
}
