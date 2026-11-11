package com.safeschoolroad.saved;

import com.safeschoolroad.evidence.EvidenceAnalyzer;
import com.safeschoolroad.evidence.GeoLine;
import com.safeschoolroad.evidence.RouteEvidence;
import com.safeschoolroad.guide.GuideWriter;
import com.safeschoolroad.guide.GuideTextValidator;
import com.safeschoolroad.route.Point;
import com.safeschoolroad.route.RouteCandidates;
import com.safeschoolroad.route.TmapGateway;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 경로 확정·저장·재조회 (4-10-09, FR-08·FR-19 서버 부분).
 * 화면은 출발·도착·candidateId·이름만 보낸다. 지점은 서버가 같은 날 캐시된 경로로 다시 계산하므로 지점을 꾸며 보낼 수 없다.
 * 경로선은 저장하지 않고, 안내할 때마다 당일 경로선을 다시 요청한다.
 */
@RestController
public class SavedRouteApi {
    private final TmapGateway tmap;
    private final EvidenceAnalyzer analyzer;
    private final SavedRouteStore store;
    private final GuideWriter guide;

    public SavedRouteApi(TmapGateway tmap, EvidenceAnalyzer analyzer, SavedRouteStore store, GuideWriter guide) {
        this.tmap = tmap;
        this.analyzer = analyzer;
        this.store = store;
        this.guide = guide;
    }

    public record SaveRequest(Point start, Point end, String candidateId, String name, Long replaceRouteId, GuideWriter.Tone tone) { }

    @PostMapping("/api/routes") ResponseEntity<?> save(@RequestBody SaveRequest req, Authentication auth) {
        Optional<Long> member = member(auth);
        if (member.isEmpty()) return error(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        if (req == null || !RouteCandidates.validRequest(req.start(), req.end()))
            return error(HttpStatus.BAD_REQUEST, "서울 범위의 서로 다른 출발·도착 좌표를 선택해 주세요.");
        String name = req.name() == null ? "" : req.name().strip();
        if (name.isEmpty() || name.length() > 30) return error(HttpStatus.BAD_REQUEST, "경로 이름은 1~30자로 입력해 주세요.");
        if (req.candidateId() == null || !req.candidateId().matches("candidate-(0|4|10)"))
            return error(HttpStatus.BAD_REQUEST, "선택한 후보가 올바르지 않습니다.");

        if (req.replaceRouteId() != null && store.find(member.get(), req.replaceRouteId()).isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "경로를 찾을 수 없습니다.");
        }
        var built = RouteCandidates.build(tmap, req.start(), req.end());
        var chosen = built.built().stream().filter(b -> b.candidate().candidateId().equals(req.candidateId())).findFirst();
        if (chosen.isEmpty()) {
            if (built.built().isEmpty()) return error(HttpStatus.SERVICE_UNAVAILABLE, "현재 실제 후보 경로를 가져올 수 없습니다.");
            return error(HttpStatus.BAD_REQUEST, "선택한 후보를 다시 확인하지 못했어요. 후보를 다시 조회해 주세요.");
        }
        RouteEvidence evidence = analyzer.analyze(req.candidateId(), chosen.get().route());
        List<RouteEvidence.KeyPoint> points = evidence.keyPoints();
        if (points.isEmpty())
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "이 경로에는 안내 지점으로 쓸 공공자료가 없어요. 다른 후보를 골라 주세요.");

        GuideWriter.Tone tone = req.tone() == null ? GuideWriter.Tone.LOWER : req.tone();
        GuideWriter.Result written = guide.write(points, tone);
        int option = chosen.get().candidate().options().get(0);
        long id;
        try {
            id = store.replace(member.get(), name, req.start(), req.end(), req.candidateId(), option,
                    points, written.texts(), written.source(), req.replaceRouteId(), tone, written.attempted());
        } catch (SavedRouteStore.MissingRoute missing) {
            return error(HttpStatus.NOT_FOUND, "경로를 찾을 수 없습니다.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "pointCount", points.size(),
                "guideSource", written.source(), "note", "경로선은 저장하지 않았어요. 안내할 때 오늘 경로선을 다시 받아요."));
    }

    @GetMapping("/api/routes") ResponseEntity<?> list(Authentication auth) {
        Optional<Long> member = member(auth);
        if (member.isEmpty()) return error(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        return ResponseEntity.ok(Map.of("routes", store.list(member.get())));
    }

    @GetMapping("/api/routes/{id}") ResponseEntity<?> detail(@PathVariable long id, Authentication auth) {
        Optional<Long> member = member(auth);
        if (member.isEmpty()) return error(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        return store.find(member.get(), id).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "경로를 찾을 수 없습니다."));
    }

    @DeleteMapping("/api/routes/{id}") ResponseEntity<?> delete(@PathVariable long id, Authentication auth) {
        Optional<Long> member = member(auth);
        if (member.isEmpty()) return error(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        return store.delete(member.get(), id) ? ResponseEntity.noContent().build()
                : error(HttpStatus.NOT_FOUND, "경로를 찾을 수 없습니다.");
    }

    public record GuideEdit(String text) { }

    @PostMapping("/api/routes/{id}/points/{seq}/guide")
    ResponseEntity<?> editGuide(@PathVariable long id, @PathVariable int seq, @RequestBody GuideEdit request,
                                Authentication auth) {
        Optional<Long> member = member(auth);
        if (member.isEmpty()) return error(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        var route = store.find(member.get(), id);
        if (route.isEmpty()) return error(HttpStatus.NOT_FOUND, "경로를 찾을 수 없습니다.");
        var point = route.get().points().stream().filter(p -> p.seq() == seq).findFirst();
        if (point.isEmpty()) return error(HttpStatus.NOT_FOUND, "안내 지점을 찾을 수 없습니다.");
        var p = point.get();
        String text = request.text() == null ? "" : request.text().strip();
        var input = new GuideWriter.Input(p.seq(), p.kind(), p.name(), p.reason(), p.signalStatus(),
                p.seq() == route.get().points().size(), p.guideText());
        if (!GuideTextValidator.valid(input, text)) {
            return error(HttpStatus.BAD_REQUEST, "지점에 맞는 안전 안내를 120자 이내로 써 주세요. 횡단보도 안내에는 멈추기, 신호, 양쪽 확인이 필요해요.");
        }
        return store.editGuide(member.get(), id, seq, text) ? ResponseEntity.noContent().build()
                : error(HttpStatus.NOT_FOUND, "안내 지점을 찾을 수 없습니다.");
    }

    @PostMapping("/api/routes/{id}/check")
    ResponseEntity<?> check(@PathVariable long id, Authentication auth) {
        Optional<Long> member = member(auth);
        if (member.isEmpty()) return error(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        var found = store.find(member.get(), id);
        if (found.isEmpty()) return error(HttpStatus.NOT_FOUND, "경로를 찾을 수 없습니다.");
        var route = found.get();
        try {
            var current = tmap.walking(route.start(), route.end(), route.searchOption());
            var line = new GeoLine(current.line());
            long moved = route.points().stream().filter(p -> line.locate(p.lat(), p.lng()).distance() > 80).count();
            return ResponseEntity.ok(Map.of("changed", moved > 0, "distantPoints", moved));
        } catch (TmapGateway.ProviderUnavailable | IllegalArgumentException failure) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "오늘 경로를 받지 못했어요. 잠시 뒤 다시 확인해 주세요.");
        }
    }

    /** 오늘 경로선 다시 받기. 저장한 출발·도착과 탐색 옵션으로 당일 캐시 또는 TMAP에서 받는다. 경로선은 저장하지 않는다. */
    @GetMapping("/api/routes/{id}/line") ResponseEntity<?> line(@PathVariable long id, Authentication auth) {
        Optional<Long> member = member(auth);
        if (member.isEmpty()) return error(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        var detail = store.find(member.get(), id);
        if (detail.isEmpty()) return error(HttpStatus.NOT_FOUND, "경로를 찾을 수 없습니다.");
        try {
            var route = tmap.walking(detail.get().start(), detail.get().end(), detail.get().searchOption());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("routeId", id, "line", route.line(),
                    "distanceM", route.distanceM(), "durationSec", route.durationSec(),
                    "note", "오늘 받은 경로선이에요. 저장한 안내 지점과 함께 보여 주고, 경로선은 저장하지 않아요."));
        } catch (TmapGateway.ProviderUnavailable e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "오늘 경로선을 받지 못했어요. 저장한 안내 지점으로 안내해요.");
        }
    }

    private Optional<Long> member(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) return Optional.empty();
        return store.memberId(auth.getName());
    }

    private static ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
