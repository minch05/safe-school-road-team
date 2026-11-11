package com.safeschoolroad.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

// 다른 테스트의 csrf() 대역 저장소와 분리해 실제 쿠키 발급·검사를 확인한다.
@SpringBootTest(properties = "app.test.security-recovery=true")
@AutoConfigureMockMvc
class ApiSecurityRecoveryTests {
    @Autowired MockMvc mvc;
    @Test void API_로그인만료는_JSON이고_일반화면은_로그인으로_이동한다() throws Exception {
        mvc.perform(get("/api/routes/1/line")).andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("AUTH"));
        mvc.perform(get("/routes/new")).andExpect(status().is3xxRedirection());
    }
    @Test void 보안토큰누락은_처리전_차단되고_읽기로_갱신된다() throws Exception {
        mvc.perform(post("/api/routes").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("code").value("CSRF"));
        var result=mvc.perform(get("/api/session")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("ready").value(true)).andReturn();
        var cookie=result.getResponse().getCookie("XSRF-TOKEN");assertThat(cookie).isNotNull();
        mvc.perform(post("/api/routes").cookie(cookie).header("X-XSRF-TOKEN",cookie.getValue()).contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("AUTH"));
    }
}
