package com.safeschoolroad.explore;

import com.safeschoolroad.external.TmapClient;
import com.safeschoolroad.geo.LatLng;
import com.safeschoolroad.route.RouteCandidateService;
import com.safeschoolroad.route.RouteDiffExplainer;
import com.safeschoolroad.route.RouteJson;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 비로그인 경로 살펴보기 API (FR-17)
@RestController
public class ExploreApiController {

    private final GuestQuota quota;
    private final RouteCandidateService candidates;
    private final TmapClient tmap;

    public ExploreApiController(GuestQuota quota, RouteCandidateService candidates, TmapClient tmap) {
        this.quota = quota;
        this.candidates = candidates;
        this.tmap = tmap;
    }

    public record PlaceInput(@NotBlank @Size(max = 30) String name,
        @NotNull @jakarta.validation.constraints.DecimalMin("-90") @jakarta.validation.constraints.DecimalMax("90") Double lat,
        @NotNull @jakarta.validation.constraints.DecimalMin("-180") @jakarta.validation.constraints.DecimalMax("180") Double lng) implements Serializable {
    }

    public record CandidateRequest(@NotNull @Valid PlaceInput start, @NotNull @Valid PlaceInput end) {
    }

    @GetMapping("/api/explore/quota")
    public Map<String, Integer> remaining(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Map.of("left", quota.left(ExploreController.device(request, response)));
    }

    @PostMapping("/api/explore/candidates")
    public Object candidates(@Valid @RequestBody CandidateRequest req, HttpServletRequest request,
                             HttpServletResponse response) {
        String device = ExploreController.device(request, response);
        LatLng start = new LatLng(req.start().lat(), req.start().lng());
        LatLng end = new LatLng(req.end().lat(), req.end().lng());
        // 같은 출발지·도착지를 오늘 이미 봤으면(세 옵션 모두 캐시에 있음) 횟수에서 빼지 않는다
        boolean cached = TmapClient.OPTIONS.stream().allMatch(o -> tmap.isCachedToday(start, end, o, List.of()));
        if (!cached && !quota.tryUse(device)) {
            response.setStatus(429);
            return Map.of("error", "오늘 로그인 없이 볼 수 있는 횟수를 다 썼어요. 가입하면 계속 쓸 수 있어요.");
        }
        List<RouteCandidateService.Candidate> list = candidates.find(start, end);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("candidates", list.stream().map(RouteJson::candidate).toList());
        res.put("merged", list.size() < 3);
        res.put("suggestion",com.safeschoolroad.route.RouteChoice.suggest(list));
        res.put("diff", list.size() >= 2 ? Map.of("text", String.join(" ", RouteDiffExplainer.facts(list)), "source", "TEMPLATE") : null);
        res.put("left", quota.left(device));
        return res;
    }

    /** 가입 뒤 이어서 저장할 수 있게 입력값을 세션에 잠깐 들고 있는다 (TMAP 응답이 아니라 사용자가 고른 좌표) */
    public record Pending(@NotNull @Valid PlaceInput start, @NotNull @Valid PlaceInput end, Integer option) implements Serializable {
    }

    @PostMapping("/api/explore/pending")
    public Map<String, Object> pending(@Valid @RequestBody Pending pending, HttpSession session) {
        session.setAttribute(ExploreController.PENDING, pending);
        return Map.of("ok", true);
    }

}
