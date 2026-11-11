package com.safeschoolroad.route;

import com.safeschoolroad.external.VworldSearchClient;
import com.safeschoolroad.family.FamilyAccess;
import com.safeschoolroad.geo.LatLng;
import com.safeschoolroad.member.CurrentMember;
import com.safeschoolroad.member.Member;
import com.safeschoolroad.place.PlaceRepository;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 경로 등록·비교·확정 화면(R-02~R-05)이 부르는 API. 로그인한 사람만.
@RestController
public class RouteApiController {

    static final String DRAFT = "routeDraft";

    private final CurrentMember current;
    private final FamilyAccess access;
    private final RouteCandidateService candidates;
    private final RouteService routes;
    private final PlaceRepository places;
    private final VworldSearchClient search;

    public RouteApiController(CurrentMember current, FamilyAccess access, RouteCandidateService candidates,
                              RouteService routes, PlaceRepository places, VworldSearchClient search) {
        this.current = current;
        this.access = access;
        this.candidates = candidates;
        this.routes = routes;
        this.places = places;
        this.search = search;
    }

    public record PlaceInput(@NotBlank @Size(max = 30) String name,
        @NotNull @jakarta.validation.constraints.DecimalMin("-90") @jakarta.validation.constraints.DecimalMax("90") Double lat,
        @NotNull @jakarta.validation.constraints.DecimalMin("-180") @jakarta.validation.constraints.DecimalMax("180") Double lng) {
        LatLng latLng() {
            return new LatLng(lat, lng);
        }

        RouteDraft.Place draft() {
            return new RouteDraft.Place(name.strip(), lat, lng);
        }
    }

    // ── 장소 ──
    @GetMapping("/api/places/search")
    public List<VworldSearchClient.Item> searchPlaces(@RequestParam String q) {
        if (q == null || q.length() > 100) throw new IllegalArgumentException("검색어는 100자 이내로 써 주세요.");
        return search.search(q);
    }

    @GetMapping("/api/places")
    public List<Map<String, Object>> myPlaces(Authentication auth, @RequestParam(required = false) Long ownerId) {
        Member owner = access.resolveOwner(current.get(auth), ownerId);
        return places.findByOwnerOrderByIdAsc(owner).stream()
                .map(p -> Map.<String, Object>of("id", p.getId(), "name", p.getName(), "lat", p.getLat(), "lng", p.getLng()))
                .toList();
    }

    // ── 후보 경로 (R-03) ──
    public record CandidateRequest(@NotNull @Valid PlaceInput start, @NotNull @Valid PlaceInput end) {
    }

    @PostMapping("/api/routes/candidates")
    public Map<String, Object> candidates(@Valid @RequestBody CandidateRequest req) {
        return candidateResponse(candidates.find(req.start().latLng(), req.end().latLng()));
    }

    static Map<String, Object> candidateResponse(List<RouteCandidateService.Candidate> list) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("candidates", list.stream().map(RouteJson::candidate).toList());
        res.put("merged", list.size() < 3);
        res.put("suggestion",RouteChoice.suggest(list));
        // 비교 대상과 수치를 정확히 명시한다. 선택 변경마다 화면이 같은 값으로 재비교하며 AI 응답을 기다리지 않는다.
        res.put("diff", list.size() >= 2 ? Map.of("text",String.join(" ",RouteDiffExplainer.facts(list)),"source","TEMPLATE") : null);
        return res;
    }

    // ── 확정 (R-04): 핵심 지점 + 안내문 초안 ──
    public record DraftRequest(@NotNull @Valid PlaceInput start, @NotNull @Valid PlaceInput end, int option,
                               Long ownerId, Route.Tone tone, Long replaceRouteId, @Size(max=64) String requestId) {
        public DraftRequest(PlaceInput start,PlaceInput end,int option,Long ownerId,Route.Tone tone,Long replaceRouteId){this(start,end,option,ownerId,tone,replaceRouteId,null);}
    }

    @PostMapping("/api/routes/draft")
    public Map<String, Object> draft(@Valid @RequestBody DraftRequest req, Authentication auth, HttpSession session) {
        String draftId=req.requestId()==null?java.util.UUID.randomUUID().toString():req.requestId();
        synchronized(session){session.setAttribute("draftRequestId",draftId);session.removeAttribute(DRAFT);}
        RouteDraft draft = routes.makeDraft(current.get(auth), req.ownerId(), req.start().draft(), req.end().draft(),
                req.option(), req.tone() == null ? Route.Tone.LOWER : req.tone(), req.replaceRouteId());
        synchronized(session){
            if(!draftId.equals(session.getAttribute("draftRequestId")))throw new IllegalArgumentException("더 최근에 만든 안내문이 있어요. 현재 안내문을 확인해 주세요.");
            session.setAttribute(DRAFT,draft);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("draftId",draftId);
        res.put("points", draft.points().stream().map(RouteJson::draftPoint).toList());
        res.put("guideSource", draft.guideSource());
        res.put("attempts", draft.guideAttempts());
        res.put("retried", Math.max(0, draft.guideAttempts() - 1));
        return res;
    }

    public record SaveRequest(@NotBlank @Size(max = 30) String name, boolean saveStart, boolean saveEnd, @Size(max=64) String draftId, @Size(max=64) String requestId) {
        public SaveRequest(String name,boolean saveStart,boolean saveEnd){this(name,saveStart,saveEnd,null,null);}
    }

    @PostMapping("/api/routes")
    public Map<String, Object> save(@Valid @RequestBody SaveRequest req, Authentication auth, HttpSession session) {
        Member member=current.get(auth);
        synchronized(session){
        var saved=(SavedRequest)session.getAttribute("lastRouteSave");
        if(req.requestId()!=null && saved!=null && req.requestId().equals(saved.requestId()) && member.getId().equals(saved.memberId()) && System.currentTimeMillis()-saved.created()<300000)return Map.of("id",saved.routeId());
        if(req.draftId()!=null && !req.draftId().equals(session.getAttribute("draftRequestId")))throw new DraftUnavailableException("안내문이 바뀌었어요. 입력한 장소와 이름은 유지돼요. 안내문을 다시 만든 뒤 확인하고 저장해 주세요.");
        RouteDraft draft = (RouteDraft) session.getAttribute(DRAFT);
        if (draft == null) {
            throw new DraftUnavailableException("저장할 안내문이 만료됐어요. 입력한 장소와 이름은 유지돼요. 안내문을 다시 만든 뒤 확인하고 저장해 주세요.");
        }
        Route route = routes.save(member, draft, req.name().strip(), req.saveStart(), req.saveEnd());
        session.removeAttribute(DRAFT);
        if(req.requestId()!=null)session.setAttribute("lastRouteSave",new SavedRequest(req.requestId(),member.getId(),route.getId(),System.currentTimeMillis()));
        return Map.of("id", route.getId());
        }
    }
    private record SavedRequest(String requestId,Long memberId,Long routeId,long created) implements java.io.Serializable {}

    // ── 경로선 다시 받기 (R-05, N-01) ──
    @GetMapping("/api/routes/{id}/line")
    public Map<String, Object> line(@PathVariable long id, Authentication auth) {
        return lineResponse(id, auth, false);
    }

    @PostMapping("/api/routes/{id}/check")
    public Map<String, Object> check(@PathVariable long id, Authentication auth) {
        return lineResponse(id, auth, true);
    }

    private Map<String, Object> lineResponse(long id, Authentication auth, boolean checkChanges) {
        Member viewer = current.get(auth);
        RouteService.LineResult result = routes.line(viewer, id, checkChanges);
        Route route = routes.get(viewer, id);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("line", RouteJson.line(result.route()));
        res.put("distance", result.route().distanceMeters());
        res.put("time", result.route().timeSeconds());
        // 아이 화면에서는 경고를 띄우지 않는다 (HANDOFF 5-4) — 걷는 사람 본인이 연동된 아이면 알리지 않음
        boolean walkerIsLinkedChild = route.getOwner().getId().equals(viewer.getId())
                && access.guardianOf(viewer).isPresent();
        res.put("changed", result.change().changed() && !walkerIsLinkedChild);
        res.put("points", route.getPoints().stream().map(RouteJson::routePoint).toList());
        return res;
    }

    // 선을 받지 못했을 때도 저장된 지점은 보여 준다
    @GetMapping("/api/routes/{id}/points")
    public List<Map<String, Object>> points(@PathVariable long id, Authentication auth) {
        return routes.get(current.get(auth), id).getPoints().stream().map(RouteJson::routePoint).toList();
    }

    // ── 안내문 고치기 (FR-10) ──
    public record GuideEdit(@NotBlank @Size(max = 200) String text) {
    }

    @PostMapping("/api/routes/{id}/points/{seq}/guide")
    public Map<String, Object> editGuide(@PathVariable long id, @PathVariable int seq, @Valid @RequestBody GuideEdit req,
                                         Authentication auth) {
        routes.editGuide(current.get(auth), id, seq, req.text().strip());
        return Map.of("ok", true);
    }
}
