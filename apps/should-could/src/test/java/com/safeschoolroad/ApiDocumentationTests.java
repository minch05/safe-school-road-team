package com.safeschoolroad;

import com.safeschoolroad.member.Member;
import com.safeschoolroad.member.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApiDocumentationTests {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;

    @Test void 비로그인_살펴보기는_공개_장소검색만_허용하고_개인_장소는_숨긴다() throws Exception {
        // 두 글자 미만은 외부 API 호출 없이 빈 결과. 인증 필터가 검색을 막지 않는지 확인한다.
        mvc.perform(get("/api/places/search").param("q", "a")).andExpect(status().isOk());
        mvc.perform(get("/api/places/search").param("q", "a".repeat(101))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/places")).andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("AUTH"));
    }

    @Test void 문서는_로그인이_필요하고_API만_문서화한다() throws Exception {
        mvc.perform(get("/v3/api-docs/service")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/swagger-ui/index.html").with(user("docs"))).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs/service").with(user("docs"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/routes/candidates']").exists())
                .andExpect(jsonPath("$.paths['/api/trips/start']").exists())
                .andExpect(jsonPath("$.paths['/api/invite/issue']").exists())
                .andExpect(jsonPath("$.paths['/settings/withdraw']").doesNotExist());
    }

    @Test void Swagger_쿠키_헤더_CSRF로_POST할_수_있고_토큰_없으면_거절한다() throws Exception {
        members.save(new Member("docsguardian", "{noop}unused", "문서검사", true));
        var page = mvc.perform(get("/settings").with(user("docsguardian"))).andReturn();
        var cookie = page.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        var matcher = java.util.regex.Pattern.compile("<meta name=\"_csrf\" content=\"([^\"]+)\"")
                .matcher(page.getResponse().getContentAsString());
        assertThat(matcher.find()).isTrue();
        mvc.perform(post("/settings/nickname").with(user("docsguardian"))
                .cookie(cookie).param("_csrf", matcher.group(1)).param("nickname", "바꾼별명"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/api/invite/issue").with(user("docsguardian")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/invite/issue").with(user("docsguardian"))
                .cookie(cookie).header("X-XSRF-TOKEN", cookie.getValue()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").exists());
        mvc.perform(post("/api/invite/issue").with(user("docsguardian"))
                .cookie(cookie).header("X-XSRF-TOKEN", "wrong"))
                .andExpect(status().isForbidden());
    }
}
