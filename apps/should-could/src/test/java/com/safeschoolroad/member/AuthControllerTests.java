package com.safeschoolroad.member;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

// 가입·로그인·자동 로그인 확인. 내 컴퓨터 MySQL을 쓰지만 @Transactional 이라 끝나면 되돌린다(데이터가 남지 않음).
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthControllerTests {

    private static final String USERNAME = "testkid01";
    private static final String PASSWORD = "password123";

    @Autowired
    MockMvc mvc;

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private MvcResult signup(String username, String password, String confirm, String nickname, boolean over14)
            throws Exception {
        var request = post("/signup").with(csrf())
                .param("username", username)
                .param("password", password)
                .param("passwordConfirm", confirm)
                .param("nickname", nickname);
        if (over14) {
            request.param("over14", "true");
        }
        return mvc.perform(request).andReturn();
    }

    @Test
    void 로그인_전_메인은_누구나_본다() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(view().name("main"));
    }

    @Test
    void 로그인이_필요한_곳은_로그인_화면으로_보낸다() throws Exception {
        mvc.perform(get("/routes")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    void 가입하면_암호화해_저장하고_바로_로그인된다() throws Exception {
        MvcResult result = signup(USERNAME, PASSWORD, PASSWORD, "하늘", true);

        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/");
        Member saved = memberRepository.findByUsername(USERNAME).orElseThrow();
        assertThat(saved.getPassword()).startsWith("{bcrypt}").doesNotContain(PASSWORD);
        assertThat(saved.getNickname()).isEqualTo("하늘");
        assertThat(saved.isOver14()).isTrue();
        // 자동 로그인 쿠키와 DB 토큰이 생긴다
        assertThat(result.getResponse().getCookie("remember-me")).isNotNull();
        assertThat(countTokens(USERNAME)).isEqualTo(1);
    }

    @Test
    void 같은_아이디로는_가입할_수_없다() throws Exception {
        long before = memberRepository.count();
        signup(USERNAME, PASSWORD, PASSWORD, "하늘", true);
        MvcResult second = signup(USERNAME, PASSWORD, PASSWORD, "바다", true);

        assertThat(second.getResponse().getStatus()).isEqualTo(200); // 가입 화면에 그대로 남는다
        assertThat(second.getResponse().getContentAsString()).contains("이미 쓰고 있는 아이디예요.");
        assertThat(memberRepository.count()).isEqualTo(before + 1);
    }

    @Test
    void 잘못된_입력은_가입되지_않는다() throws Exception {
        long before = memberRepository.count();
        assertThat(signup("AB", PASSWORD, PASSWORD, "하늘", true).getResponse().getContentAsString())
                .contains("영문 소문자와 숫자로 4~20자를 써 주세요.");
        assertThat(signup(USERNAME, "short", "short", "하늘", true).getResponse().getContentAsString())
                .contains("비밀번호는 8자 이상으로 써 주세요.");
        assertThat(signup(USERNAME, PASSWORD, "different1", "하늘", true).getResponse().getContentAsString())
                .contains("비밀번호가 서로 달라요.");
        assertThat(signup(USERNAME, PASSWORD, PASSWORD, "  ", true).getResponse().getContentAsString())
                .contains("별명을 써 주세요.");
        assertThat(signup(USERNAME, PASSWORD, PASSWORD, "하늘", false).getResponse().getContentAsString())
                .contains("만 14세 이상만 직접 가입할 수 있어요.");
        assertThat(memberRepository.count()).isEqualTo(before);
    }

    @Test
    void 중복_확인() throws Exception {
        signup(USERNAME, PASSWORD, PASSWORD, "하늘", true);
        mvc.perform(get("/signup/check-username").param("username", USERNAME))
                .andExpect(jsonPath("$.available").value(false));
        mvc.perform(get("/signup/check-username").param("username", "newkid99"))
                .andExpect(jsonPath("$.available").value(true));
        mvc.perform(get("/signup/check-username").param("username", "Bad!"))
                .andExpect(jsonPath("$.available").value(false));
    }

    @Test
    void 로그인_성공과_실패() throws Exception {
        signup(USERNAME, PASSWORD, PASSWORD, "하늘", true);

        mvc.perform(post("/login").with(csrf()).param("username", USERNAME).param("password", "wrongpass1"))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());

        MvcResult ok = mvc.perform(post("/login").with(csrf()).param("username", USERNAME).param("password", PASSWORD))
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername(USERNAME))
                .andReturn();
        assertThat(ok.getResponse().getCookie("remember-me")).isNotNull();
    }

    @Test
    void 자동_로그인_쿠키만_있어도_홈이_열린다() throws Exception {
        MvcResult result = signup(USERNAME, PASSWORD, PASSWORD, "하늘", true);
        Cookie rememberMe = result.getResponse().getCookie("remember-me");

        // 세션 없이 쿠키만 들고 온 경우 = 브라우저를 껐다 켠 경우
        mvc.perform(get("/").cookie(rememberMe))
                .andExpect(view().name("home"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("하늘")));
    }

    @Test
    void 로그아웃하면_자동_로그인_토큰도_지운다() throws Exception {
        MvcResult result = signup(USERNAME, PASSWORD, PASSWORD, "하늘", true);
        Cookie rememberMe = result.getResponse().getCookie("remember-me");

        // 브라우저처럼 가입 때 받은 세션을 들고 로그아웃 버튼을 누른다
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        mvc.perform(post("/logout").with(csrf()).session(session).cookie(rememberMe))
                .andExpect(redirectedUrl("/"));
        assertThat(countTokens(USERNAME)).isZero();
        mvc.perform(get("/").cookie(rememberMe)).andExpect(view().name("main"));
    }

    private int countTokens(String username) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM persistent_logins WHERE username = ?", Integer.class, username);
        return n == null ? 0 : n;
    }
}
