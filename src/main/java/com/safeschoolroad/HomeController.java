package com.safeschoolroad;

import com.safeschoolroad.explore.ExploreController;
import com.safeschoolroad.family.FamilyAccess;
import com.safeschoolroad.family.FamilyService;
import com.safeschoolroad.member.CurrentMember;
import com.safeschoolroad.member.Member;
import com.safeschoolroad.route.Route;
import com.safeschoolroad.route.RouteService;
import com.safeschoolroad.util.Korean;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;
import java.util.Optional;

// "/" 는 사람에 따라 다른 홈을 보여 준다. 계정은 한 종류이고, 화면은 연동 상태로 나뉜다(HANDOFF 3장).
//  - 로그인 전: 메인(C-01)
//  - 자녀와 연동된 보호자: 보호자 홈(P-01)
//  - 보호자와 연동된 자녀: 아이 홈(K-01)
//  - 그 밖(개인): 내 경로 홈(R-01)
@Controller
public class HomeController {

    private final CurrentMember current;
    private final FamilyAccess access;
    private final FamilyService family;
    private final RouteService routes;

    public HomeController(CurrentMember current, FamilyAccess access, FamilyService family, RouteService routes) {
        this.current = current;
        this.access = access;
        this.family = family;
        this.routes = routes;
    }

    @GetMapping("/")
    public String home(Authentication authentication, Model model, HttpSession session) {
        Optional<Member> found = current.find(authentication);
        if (found.isEmpty()) {
            return "main";
        }
        // 로그인 없이 비교하던 경로가 있으면 이어서 저장하러 간다 (HANDOFF 5-7)
        if (session.getAttribute(ExploreController.PENDING) != null) {
            return "redirect:/routes/new?continue=1";
        }
        Member me = found.get();
        model.addAttribute("nickname", me.getNickname());
        List<Route> mine = routes.listOf(me);
        model.addAttribute("routes", mine);
        // 홈 지도에 찍을 출발지·도착지 (사용자가 고른 좌표만)
        model.addAttribute("routeEnds", mine.stream().map(r -> Map.<String, Object>of("name", r.getName(),
                "startLat", r.getStartLat(), "startLng", r.getStartLng(),
                "endLat", r.getEndLat(), "endLng", r.getEndLng())).toList());
        if (access.isGuardian(me)) {
            model.addAttribute("children", family.childSummaries(me));
            return "home-parent";
        }
        if (access.guardianOf(me).isPresent()) {
            model.addAttribute("greeting", Korean.withVocative(me.getNickname()) + ", 어디 가?");
            return "home-kid";
        }
        return "home";
    }
}
