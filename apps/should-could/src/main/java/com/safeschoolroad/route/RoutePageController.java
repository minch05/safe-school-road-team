package com.safeschoolroad.route;

import com.safeschoolroad.explore.ExploreApiController;
import com.safeschoolroad.explore.ExploreController;
import com.safeschoolroad.family.FamilyAccess;
import com.safeschoolroad.member.CurrentMember;
import com.safeschoolroad.member.Member;
import com.safeschoolroad.trip.TripService;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

// 경로 화면: R-02~R-04 등록(한 화면 안에서 단계별), R-05 상세, N-01 길 안내, N-02 도착
@Controller
public class RoutePageController {

    private final CurrentMember current;
    private final FamilyAccess access;
    private final RouteService routes;
    private final TripService trips;

    public RoutePageController(CurrentMember current, FamilyAccess access, RouteService routes, TripService trips) {
        this.current = current;
        this.access = access;
        this.routes = routes;
        this.trips = trips;
    }

    // R-02 경로 등록. ownerId: 보호자가 아이 경로를 대신 등록할 때. replace: 다시 등록(기존 경로를 대신함)
    @GetMapping("/routes/new")
    public String newRoute(@RequestParam(required = false) Long ownerId, @RequestParam(required = false) Long replace,
                           @RequestParam(name = "continue", defaultValue = "false") boolean continuePending,
                           Authentication auth, Model model, HttpSession session) {
        Member viewer = current.get(auth);
        Member owner = access.resolveOwner(viewer, ownerId);
        model.addAttribute("ownerId", owner.getId().equals(viewer.getId()) ? null : owner.getId());
        model.addAttribute("ownerName", owner.getId().equals(viewer.getId()) ? null : owner.getNickname());
        Route old = replace == null ? null : routes.get(viewer, replace);
        model.addAttribute("replace", old);
        if (old != null) {
            model.addAttribute("replaceStart", Map.of("name", old.getStartName(), "lat", old.getStartLat(), "lng", old.getStartLng()));
            model.addAttribute("replaceEnd", Map.of("name", old.getEndName(), "lat", old.getEndLat(), "lng", old.getEndLng()));
        }
        model.addAttribute("guest", false);
        // 비로그인으로 비교하던 경로를 가입·로그인 뒤 이어서 (입력값은 한 번 쓰고 지운다)
        Object pending = session.getAttribute(ExploreController.PENDING);
        if (continuePending && pending instanceof ExploreApiController.Pending p) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("start", Map.of("name", p.start().name(), "lat", p.start().lat(), "lng", p.start().lng()));
            m.put("end", Map.of("name", p.end().name(), "lat", p.end().lat(), "lng", p.end().lng()));
            m.put("option", p.option() == null ? 0 : p.option());
            model.addAttribute("pending", m);
        }
        session.removeAttribute(ExploreController.PENDING);
        return "route-new";
    }

    // R-05 경로 상세
    @GetMapping("/routes/{id}")
    public String detail(@PathVariable long id, Authentication auth, Model model) {
        Member viewer = current.get(auth);
        Route route = routes.get(viewer, id);
        model.addAttribute("route", route);
        boolean mine = route.getOwner().getId().equals(viewer.getId());
        model.addAttribute("ownerName", mine ? null : route.getOwner().getNickname());
        // 연동된 아이 본인이 보는 화면이면 달라짐 경고·삭제를 보여 주지 않는다 (HANDOFF 5-4)
        model.addAttribute("kidView", mine && access.guardianOf(viewer).isPresent());
        model.addAttribute("backUrl", mine ? "/" : "/children/" + route.getOwner().getId());
        return "route-detail";
    }

    @PostMapping("/routes/{id}/delete")
    public String delete(@PathVariable long id, Authentication auth, RedirectAttributes flash) {
        Member viewer = current.get(auth);
        Route route = routes.get(viewer, id);
        boolean mine = route.getOwner().getId().equals(viewer.getId());
        long ownerId = route.getOwner().getId();
        routes.delete(viewer, id);
        flash.addFlashAttribute("toast", "'" + route.getName() + "' 경로를 삭제했어요");
        return mine ? "redirect:/" : "redirect:/children/" + ownerId;
    }

    // N-01 길 안내
    @GetMapping("/routes/{id}/navigate")
    public String navigate(@PathVariable long id, Authentication auth, Model model) {
        Member viewer = current.get(auth);
        Route route = routes.get(viewer, id);
        model.addAttribute("route", route);
        // 출발·도착 이벤트는 보호자와 연동된 사람이 자기 경로를 걸을 때만 보낸다 (개인 사용자는 보내지 않음, HANDOFF 3장)
        boolean sendsEvents = route.getOwner().getId().equals(viewer.getId()) && access.guardianOf(viewer).isPresent();
        model.addAttribute("sendsEvents", sendsEvents);
        return "navigate";
    }

    // N-02 도착
    @GetMapping("/routes/{id}/arrived")
    public String arrived(@PathVariable long id, @RequestParam(required = false) Long tripId,
                          Authentication auth, Model model) {
        Member viewer = current.get(auth);
        Route route = routes.get(viewer, id);
        boolean familyTrip = route.getOwner().getId().equals(viewer.getId()) && access.guardianOf(viewer).isPresent();
        model.addAttribute("route", route);
        model.addAttribute("recorded", trips.arrivalRecorded(viewer, id, tripId));
        model.addAttribute("familyTrip", familyTrip);
        return "arrived";
    }
}
