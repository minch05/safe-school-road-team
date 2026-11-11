package com.safeschoolroad.route;

import com.safeschoolroad.external.TmapClient;
import com.safeschoolroad.external.TmapRoute;
import com.safeschoolroad.facility.FacilityKind;
import com.safeschoolroad.family.FamilyAccess;
import com.safeschoolroad.geo.LatLng;
import com.safeschoolroad.guide.GuideLog;
import com.safeschoolroad.guide.GuideLogRepository;
import com.safeschoolroad.guide.GuidePoint;
import com.safeschoolroad.guide.GuideService;
import com.safeschoolroad.guide.GuideValidator;
import com.safeschoolroad.member.Member;
import com.safeschoolroad.member.MemberRepository;
import com.safeschoolroad.place.Place;
import com.safeschoolroad.place.PlaceRepository;
import com.safeschoolroad.route.analysis.RouteAnalysis;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

// 경로 확정·저장·조회·삭제 (FR-08, 09, 11, 22)
@Service
public class RouteService {

    static final int MAX_WAYPOINTS = 5;

    private final RouteCandidateService candidates;
    private final RouteRepository routes;
    private final PlaceRepository places;
    private final MemberRepository members;
    private final FamilyAccess access;
    private final GuideService guides;
    private final GuideLogRepository guideLogs;
    private final TmapClient tmap;

    public RouteService(RouteCandidateService candidates, RouteRepository routes, PlaceRepository places,
                        MemberRepository members, FamilyAccess access, GuideService guides,
                        GuideLogRepository guideLogs, TmapClient tmap) {
        this.candidates = candidates;
        this.routes = routes;
        this.places = places;
        this.members = members;
        this.access = access;
        this.guides = guides;
        this.guideLogs = guideLogs;
        this.tmap = tmap;
    }

    // ── 확정 (R-04): 핵심 지점 + 안내문을 만들어 초안으로 돌려준다. 저장은 save() ──
    // TMAP·AI 를 기다리는 동안 DB 연결을 잡고 있지 않도록 트랜잭션을 걸지 않는다
    public RouteDraft makeDraft(Member viewer, Long ownerId, RouteDraft.Place start, RouteDraft.Place end, int option,
                                Route.Tone tone, Long replaceRouteId) {
        Member owner = access.resolveOwner(viewer, ownerId);
        if (replaceRouteId != null) {
            Route old = getManaged(viewer, replaceRouteId);
            if (!old.getOwner().getId().equals(owner.getId())) {
                throw new AccessDeniedException("다른 사람의 경로");
            }
        }
        RouteAnalysis analysis = candidates.analyzeOption(
                new LatLng(start.lat(), start.lng()), new LatLng(end.lat(), end.lng()), option);

        List<RouteAnalysis.KeyPoint> keyPoints = analysis.keyPoints();
        List<GuidePoint> guidePoints = new ArrayList<>();
        for (int i = 0; i < keyPoints.size(); i++) {
            RouteAnalysis.KeyPoint kp = keyPoints.get(i);
            guidePoints.add(new GuidePoint(i + 1, kp.facility().kind(), kp.facility().name(),
                    kp.facility().signalStatus(), kp.reason(), i == keyPoints.size() - 1, kp.turnDirection()));
        }
        GuideService.GuideResult guide = guides.write(guidePoints, tone);
        GuideLog log = guideLogs.save(new GuideLog(guide.model(), tone.name(),
                guidePoints.size(), guide.attempts(), guide.source(), guide.errors()));

        List<RouteDraft.Point> points = new ArrayList<>();
        for (int i = 0; i < keyPoints.size(); i++) {
            RouteAnalysis.KeyPoint kp = keyPoints.get(i);
            points.add(new RouteDraft.Point(i + 1, kp.facility().source(), kp.facility().sourceId(),
                    kp.facility().kind(), kp.facility().name(), kp.facility().lat(), kp.facility().lng(),
                    kp.facility().signalStatus(), kp.reason(), kp.distanceToLine(), guide.texts().get(i + 1)));
        }
        return new RouteDraft(owner.getId(), start, end, option, tone, replaceRouteId, points,
                guide.source(), guide.attempts(), guide.errors(), log.getId());
    }

    @Transactional
    public Route save(Member viewer, RouteDraft draft, String name, boolean saveStartPlace, boolean saveEndPlace) {
        Member owner = access.resolveOwner(viewer, draft.ownerId());
        Route route = new Route(owner, viewer, name,
                draft.start().name(), draft.start().lat(), draft.start().lng(),
                draft.end().name(), draft.end().lat(), draft.end().lng(), draft.option(), draft.tone());
        for (RouteDraft.Point p : draft.points()) {
            RoutePoint point = new RoutePoint(route, p.seq(), p.source(), p.sourceId(), p.kind(), p.name(),
                    p.lat(), p.lng(), p.signalStatus(), p.reason(), p.distanceToLine());
            point.setGuideText(p.guideText());
            route.addPoint(point);
        }
        route.recordGuide(draft.guideSource(), draft.guideAttempts());
        routes.save(route);
        guideLogs.findById(draft.guideLogId()).ifPresent(l -> l.linkRoute(route.getId()));

        if (saveStartPlace) {
            savePlace(owner, draft.start());
        }
        if (saveEndPlace) {
            savePlace(owner, draft.end());
        }
        // 다시 등록: 새 경로가 기존 경로를 대신한다 (기존 경로 수정은 없음, HANDOFF 4장)
        if (draft.replaceRouteId() != null) {
            routes.findById(draft.replaceRouteId())
                    .filter(old -> old.getOwner().getId().equals(owner.getId()))
                    .ifPresent(routes::delete);
        }
        return route;
    }

    // 같은 이름의 장소가 있으면 좌표만 새로 바꾼다
    private void savePlace(Member owner, RouteDraft.Place p) {
        places.findByOwnerAndName(owner, p.name()).ifPresentOrElse(
                existing -> existing.move(p.lat(), p.lng()),
                () -> places.save(new Place(owner, p.name(), p.lat(), p.lng())));
    }

    // ── 조회 ──
    @Transactional(readOnly = true)
    public List<Route> listOf(Member owner) {
        List<Route> list = routes.findByOwnerOrderByIdAsc(owner);
        list.forEach(r -> r.getPoints().size()); // 화면에서 쓰도록 미리 읽어 둔다
        return list;
    }

    @Transactional(readOnly = true)
    public Route get(Member viewer, long routeId) {
        Route route = getManaged(viewer, routeId);
        route.getPoints().size();
        route.getOwner().getNickname();
        return route;
    }

    private Route getManaged(Member viewer, long routeId) {
        Route route = routes.findById(routeId).orElseThrow(() -> new NoSuchElementException("없는 경로"));
        if (!access.canManage(viewer, route.getOwner())) {
            throw new AccessDeniedException("볼 수 없는 경로");
        }
        return route;
    }

    @Transactional
    public void delete(Member viewer, long routeId) {
        routes.delete(getManaged(viewer, routeId));
    }

    // ── 경로선 다시 받기 + 달라짐 확인 (HANDOFF 5-4) ──
    public record LineResult(TmapRoute route, ChangeDetector.Result change) {
    }

    /** 저장된 횡단보도를 경유지로 넣고 확정 때와 같은 탐색 옵션으로 다시 받는다. 당일에는 캐시를 쓴다 */
    @Transactional
    public LineResult line(Member viewer, long routeId) {
        return line(viewer, routeId, true);
    }

    @Transactional
    public LineResult line(Member viewer, long routeId, boolean checkChanges) {
        Route route = getManaged(viewer, routeId);
        List<RoutePoint> crossings = route.getPoints().stream()
                .filter(p -> p.getKind() == FacilityKind.CROSSWALK).toList();
        List<LatLng> waypoints = ChangeDetector.spread(crossings, MAX_WAYPOINTS).stream()
                .map(p -> new LatLng(p.getLat(), p.getLng())).toList();
        LatLng start = new LatLng(route.getStartLat(), route.getStartLng());
        LatLng end = new LatLng(route.getEndLat(), route.getEndLng());
        TmapRoute line = tmap.pedestrian(start, end, route.getSearchOption(), waypoints);

        List<ChangeDetector.SavedPoint> saved = route.getPoints().stream()
                .map(p -> new ChangeDetector.SavedPoint(p.getLat(), p.getLng(), p.getDistanceAtConfirm())).toList();
        ChangeDetector.Result change = checkChanges
                ? ChangeDetector.check(start, end, saved, line.line())
                : new ChangeDetector.Result(false, 0, 1);
        if (checkChanges) route.recordChangeCheck(change.changed());
        return new LineResult(line, change);
    }

    // ── 안내문 고치기 (FR-10) ──
    @Transactional
    public void editGuide(Member viewer, long routeId, int seq, String text) {
        Route route = getManaged(viewer, routeId);
        RoutePoint point = route.getPoints().stream().filter(p -> p.getSeq() == seq).findFirst()
                .orElseThrow(() -> new NoSuchElementException("없는 지점"));
        if (point.getKind() == FacilityKind.CROSSWALK) {
            var errors = GuideValidator.crossingErrors(text, point.getSignalStatus());
            if (!errors.isEmpty()) throw new IllegalArgumentException(errors.getFirst());
        }
        point.editGuideText(text);
    }

    @Transactional(readOnly = true)
    public Member member(long id) {
        return members.findById(id).orElseThrow();
    }
}
