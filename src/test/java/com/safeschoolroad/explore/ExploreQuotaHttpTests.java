package com.safeschoolroad.explore;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
class ExploreQuotaHttpTests {
    @Autowired MockMvc mvc;
    @Autowired GuestQuota quota;
    @Test void 비로그인_횟수읽기는_보안필터를_통과하고_캐시하지않는다() throws Exception {
        String device=UUID.randomUUID().toString();
        mvc.perform(get("/api/explore/quota").cookie(new Cookie("ssr_device",device)))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().string("X-Content-Type-Options","nosniff"))
            .andExpect(jsonPath("left").value(quota.perDevice()));
        org.assertj.core.api.Assertions.assertThat(quota.used(device)).isZero();
    }
    @Test void 이름검증실패는_실제컨트롤러에서_횟수를쓰지않는다() throws Exception {
        String device=UUID.randomUUID().toString();
        mvc.perform(post("/api/explore/candidates").cookie(new Cookie("ssr_device",device)).with(csrf())
            .contentType("application/json")
            .content("{\"start\":{\"name\":\"\",\"lat\":37.59,\"lng\":126.94},\"end\":{\"name\":\"도착\",\"lat\":37.6,\"lng\":126.95}}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("error").value("입력한 값을 확인해 주세요."));
        org.assertj.core.api.Assertions.assertThat(quota.used(device)).isZero();
    }
    @Test void 누락과_범위밖좌표는_외부요청전에_거절한다() throws Exception {
        String device=UUID.randomUUID().toString();
        for(String point:new String[]{"{\"name\":\"출발\",\"lng\":126.94}","{\"name\":\"출발\",\"lat\":91,\"lng\":126.94}","{\"name\":\"출발\",\"lat\":37.59,\"lng\":181}","{\"name\":\"출발\",\"lat\":null,\"lng\":126.94}"}) {
            String body="{\"start\":"+point+",\"end\":{\"name\":\"도착\",\"lat\":37.6,\"lng\":126.95}}";
            for(String url:new String[]{"/api/explore/candidates","/api/explore/pending","/api/routes/candidates","/api/routes/draft"})
                mvc.perform(post(url).cookie(new Cookie("ssr_device",device)).with(csrf())
                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("coordinate-test"))
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        org.assertj.core.api.Assertions.assertThat(quota.used(device)).isZero();
    }
    @Test void 깨진입력은_내부오류없이_다시선택안내를준다() throws Exception {
        String device=UUID.randomUUID().toString();
        for(String body:new String[]{"{", "{\"start\":{\"name\":\"출발\",\"lat\":\"잘못된숫자\",\"lng\":126.94}}"})
            mvc.perform(post("/api/explore/candidates").cookie(new Cookie("ssr_device",device)).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("error").value("입력 형식을 확인해 주세요. 장소를 다시 고른 뒤 시도해 주세요."));
        org.assertj.core.api.Assertions.assertThat(quota.used(device)).isZero();
    }
}
