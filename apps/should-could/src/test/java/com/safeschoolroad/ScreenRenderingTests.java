package com.safeschoolroad;

import com.safeschoolroad.family.FamilyService;
import com.safeschoolroad.member.Member;
import com.safeschoolroad.member.MemberRepository;
import com.safeschoolroad.place.Place;
import com.safeschoolroad.place.PlaceRepository;
import com.safeschoolroad.route.Route;
import com.safeschoolroad.route.RouteRepository;
import com.safeschoolroad.trip.Trip;
import com.safeschoolroad.trip.TripRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

// 역할별 조건문과 빈 목록/긴 이름이 포함된 실제 Thymeleaf 화면을 렌더링한다.
// DB 데이터는 테스트가 끝나면 롤백. 선택적 HTML 출력은 모바일 시각 검수용이다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ScreenRenderingTests {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired FamilyService family;
    @Autowired RouteRepository routes;
    @Autowired PlaceRepository places;
    @Autowired TripRepository trips;

    private Member adult(String username) {
        return members.save(new Member(username, "{noop}unused", "미리보기", true));
    }

    private String screen(Member member, String url, String template, String file) throws Exception {
        var request = get(url);
        if (member != null) request.with(user(member.getUsername()));
        var result = mvc.perform(request).andExpect(status().isOk())
                .andExpect(view().name(template)).andReturn();
        String output = System.getenv("SSR_SCREEN_PREVIEW_DIR");
        if (output != null) {
            Files.createDirectories(Path.of(output));
            String html = result.getResponse().getContentAsString();
            // 실제 API 키·CSRF 값은 검수용 정적 파일에 복사하지 않는다.
            html = html.replaceAll("<script[^>]*oapi\\.map\\.naver\\.com[^>]*></script>", "");
            html = html.replaceAll("<meta name=\"(?:vworld-key|vapid-key|_csrf|_csrf_header)\"[^>]*>", "");
            html = html.replace("<script src=\"/js/app.js\"></script>",
                    "<script src=\"/js/app.js\"></script><script src=\"/preview.js\"></script>");
            Files.writeString(Path.of(output, file + ".html"), html);
        }
        return result.getResponse().getContentAsString();
    }

    @Test
    void 개인_화면과_빈_목록이_렌더링된다() throws Exception {
        Member member = adult("screenpersonal");
        screen(null, "/", "main", "main");
        screen(null, "/map", "safety-map", "safety-map");
        screen(null,"/data-info","data-info","data-info");
        screen(null, "/login", "login", "login");
        screen(null, "/signup", "signup-age", "signup");
        screen(member, "/", "home", "home-empty");
        screen(member, "/places", "places", "places-empty");
        screen(member, "/settings", "settings", "settings-personal");
        screen(member, "/invite", "invite", "invite");
        screen(member, "/routes/new", "route-new", "route-new");
        places.save(new Place(member, "학교 정문에서 가까운 우리 집 앞 출입구", 37.59, 127.08));
        screen(member, "/places", "places", "places");
        Route route = routes.save(new Route(member, member, "학교 정문까지 조심해서 걸어가는 길", "우리 집 앞 출입구", 37.59, 127.08,
                "학교 정문", 37.6, 127.09, 0, Route.Tone.LOWER));
        route.recordChangeCheck(true);
        screen(member, "/", "home", "home");
        screen(member, "/routes/" + route.getId(), "route-detail", "route-detail");
        screen(member, "/routes/" + route.getId() + "/navigate", "navigate", "navigate");
        screen(member, "/routes/" + route.getId() + "/arrived?notified=true", "arrived", "arrived");
    }

    @Test
    void 보호자와_아이_화면이_연동_상태에_맞게_렌더링된다() throws Exception {
        Member parent = adult("screenparent");
        Member child = family.createChild(parent, "screenchild", "password1", "하늘");
        screen(child, "/", "home-kid", "home-kid-empty");
        screen(parent, "/children/" + child.getId(), "child-detail", "child-detail-empty");
        routes.save(new Route(child, parent, "학교 정문까지 조심해서 걸어가는 길", "우리 집 앞 출입구", 37.59, 127.08,
                "초등학교 정문", 37.6, 127.09, 0, Route.Tone.LOWER));
        Trip trip = trips.save(new Trip(child, null, "학교 정문까지 조심해서 걸어가는 길", 600));
        screen(parent, "/", "home-parent", "home-parent");
        screen(child, "/", "home-kid", "home-kid");
        screen(parent, "/children/" + child.getId(), "child-detail", "child-detail");
        // 연동된 아이 홈에는 아래 메뉴가 없으니 아이 설정에서도 숨긴다. 보호자는 그대로
        org.assertj.core.api.Assertions.assertThat(screen(parent, "/settings", "settings", "settings-parent")).contains("bottom-nav");
        org.assertj.core.api.Assertions.assertThat(screen(child, "/settings", "settings", "settings-kid")).doesNotContain("class=\"bottom-nav\"");
        org.assertj.core.api.Assertions.assertThat(screen(parent, "/", "home-parent", "home-parent-snooze")).contains("pushLater").contains("inline-help");
        trip.arrive(false);
        screen(parent, "/", "home-parent", "home-parent-arrived");
        Member teen = adult("screenteen");
        family.redeemInvite(teen, family.issueInvite(parent).getCode());
        screen(teen, "/settings", "settings", "settings-teen");
    }

    @Test
    void 없는_아이_주소는_쉬운_404_화면으로_보인다() throws Exception {
        Member guardian = adult("errguardian");
        var result = mvc.perform(get("/children/999999999").with(user(guardian.getUsername())))
                .andExpect(status().isNotFound()).andExpect(view().name("error")).andReturn();
        String html = result.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(html).contains("찾는 화면이 없어요").doesNotContain("Exception");
    }

    @Test
    void 로그인과_가입_화면은_고친_칸의_오류를_지우고_아이디를_되살린다() throws Exception {
        String login = mvc.perform(get("/login").param("error", "")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(login).contains("sessionStorage.getItem('loginId')");
        String signup = mvc.perform(get("/signup/form")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(signup).contains("err.remove()");
    }
}
