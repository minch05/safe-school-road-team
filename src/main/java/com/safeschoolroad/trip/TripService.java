package com.safeschoolroad.trip;

import com.safeschoolroad.family.FamilyAccess;
import com.safeschoolroad.family.GuardianLink;
import com.safeschoolroad.member.Member;
import com.safeschoolroad.push.PushNotifications;
import com.safeschoolroad.route.Route;
import com.safeschoolroad.route.RouteService;
import com.safeschoolroad.util.Korean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.NoSuchElementException;

// 출발·도착 알림 (FR-20, HANDOFF 5-6). 보호자와 연동된 사람이 자기 경로를 걸을 때만.
// 서버가 받는 것은 "출발함(+그날 예상 소요 시간) / 도착함"뿐, 위치는 받지 않는다.
@Service
public class TripService {

    private static final Logger log = LoggerFactory.getLogger(TripService.class);
    // 예상 도착 + 15분이 지나도 도착 이벤트가 없으면 한 번 알린다 (HANDOFF 5-6: 10분은 헛알림, 20분은 늦음)
    static final Duration DELAY_GRACE = Duration.ofMinutes(15);
    // 예상 소요 시간으로 받을 수 있는 최대값 (잘못된 값 방지)
    static final int MAX_EXPECTED_SECONDS = 3 * 60 * 60;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final TripRepository trips;
    private final RouteService routes;
    private final FamilyAccess access;
    private final PushNotifications push;
    private final com.safeschoolroad.member.MemberRepository members;

    public TripService(TripRepository trips, RouteService routes, FamilyAccess access, PushNotifications push, com.safeschoolroad.member.MemberRepository members) {
        this.trips = trips;
        this.routes = routes;
        this.access = access;
        this.push = push;
        this.members = members;
    }

    @Transactional
    public Trip start(Member child, long routeId, int expectedSeconds) {
        return start(child, routeId, expectedSeconds, null);
    }

    @Transactional
    public Trip start(Member child, long routeId, int expectedSeconds, String requestId) {
        members.lockForTripStart(child.getId()).orElseThrow(() -> new NoSuchElementException("없는 회원"));
        Route route = routes.get(child, routeId);
        if (!route.getOwner().getId().equals(child.getId())) {
            throw new AccessDeniedException("자기 경로만 출발할 수 있어요");
        }
        GuardianLink link = access.guardianOf(child)
                .orElseThrow(() -> new IllegalStateException("보호자와 연동되지 않아 알림을 보내지 않아요."));
        if (requestId != null) {
            try {
                String canonical = java.util.UUID.fromString(requestId).toString();
                if (!canonical.equalsIgnoreCase(requestId)) throw new IllegalArgumentException();
                requestId = canonical;
            }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("출발 요청 번호가 올바르지 않아요."); }
            var existing = trips.findByStartRequestId(requestId);
            if (existing.isPresent()) {
                Trip previous = existing.get();
                if (!previous.getChild().getId().equals(child.getId()) || !Long.valueOf(routeId).equals(previous.getRouteId()))
                    throw new AccessDeniedException("다른 출발 요청");
                return previous;
            }
        }
        int expected = Math.max(60, Math.min(expectedSeconds, MAX_EXPECTED_SECONDS));
        Trip trip = trips.save(new Trip(child, route.getId(), route.getName(), expected, requestId));
        push.send(link.getGuardian(), child.getNickname() + " 출발",
                Korean.withSubject(child.getNickname()) + " '" + route.getName() + "' 길을 출발했어요. (예상 도착 "
                        + trip.expectedArrival().format(HM) + ")",
                "/children/" + child.getId(), link.getId(), trip.getId(), "START");
        return trip;
    }

    @Transactional
    public void arrive(Member child, long tripId, boolean auto) {
        Trip trip = trips.findById(tripId).orElseThrow(() -> new NoSuchElementException("없는 기록"));
        if (!trip.getChild().getId().equals(child.getId())) {
            throw new AccessDeniedException("다른 사람의 기록");
        }
        if (trip.getArrivedAt() != null) {
            return;
        }
        if (trips.recordArrival(tripId, child.getId(), LocalDateTime.now(), auto) != 1) return;
        // 벌크 갱신은 영속성 컨텍스트를 비우므로 DB에 기록된 시각/방식을 다시 읽는다.
        Trip arrived = trips.findById(tripId).orElseThrow(() -> new NoSuchElementException("없는 기록"));
        access.guardianOf(child).ifPresent(link -> push.send(link.getGuardian(), child.getNickname() + " 도착",
                Korean.withSubject(child.getNickname()) + " '" + arrived.getRouteName() + "' 목적지에 도착했어요. ("
                        + arrived.getArrivedAt().format(HM) + ")",
                "/children/" + child.getId(), link.getId(), arrived.getId(), "ARRIVAL"));
    }

    /** 도착 화면도 실제 기록을 확인한다. URL 값만으로 알림 성공을 표시하지 않는다. */
    @Transactional(readOnly = true)
    public boolean arrivalRecorded(Member viewer, long routeId, Long tripId) {
        if (tripId == null) return false;
        return trips.findById(tripId).filter(t -> t.getChild().getId().equals(viewer.getId()))
                .filter(t -> Long.valueOf(routeId).equals(t.getRouteId()))
                .filter(t -> t.getArrivedAt() != null).isPresent();
    }

    // 1분마다: 도착 지연 알림 (출발 + 예상 소요 + 15분)
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    @Transactional
    public void checkDelays() {
        LocalDateTime now = LocalDateTime.now();
        for (Trip t : trips.findOpen(now.minusHours(24))) {
            if (t.getExpectedSeconds() > 0 && t.expectedArrival().plus(DELAY_GRACE).isBefore(now)) {
                if (trips.recordDelayAlert(t.getId(), now, now.minusHours(24), t.getExpectedSeconds()) != 1) continue;
                access.guardianOf(t.getChild()).ifPresent(link -> push.send(link.getGuardian(),
                        t.getChild().getNickname() + " 도착 표시 없음",
                        Korean.withSubject(t.getChild().getNickname()) + " 아직 '" + t.getRouteName() + "' 도착 표시를 하지 않았어요. ("
                                + t.getStartedAt().format(HM) + " 출발, 예상 " + t.expectedArrival().format(HM) + " 도착)",
                        "/children/" + t.getChild().getId(), link.getId(), t.getId(), "DELAY"));
                log.info("도착 지연 알림: trip {}", t.getId());
            }
        }
    }

    // 매시 정각: 하루 지난 기록의 예상 소요 시간(TMAP 응답)을 지운다. 출발·도착 시각은 남긴다
    @Scheduled(cron = "0 5 * * * *", zone = "Asia/Seoul")
    @Transactional
    public void clearOldExpectedTimes() {
        int n = trips.clearExpectedBefore(LocalDateTime.now().minusHours(24));
        if (n > 0) {
            log.info("하루 지난 출발 기록 {}건의 예상 소요 시간을 지웠습니다", n);
        }
    }

    /** 보호자 화면용 오늘 상태 */
    public enum TodayState {
        NOT_STARTED("출발 전"), WALKING("도착 기록 기다리는 중"), ARRIVED("도착"), LATE("도착 표시 없음");

        private final String label;

        TodayState(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public static TodayState stateOf(Trip t, LocalDateTime now) {
        if (t == null) {
            return TodayState.NOT_STARTED;
        }
        if (t.getArrivedAt() != null) {
            return TodayState.ARRIVED;
        }
        return t.expectedArrival().plus(DELAY_GRACE).isBefore(now) ? TodayState.LATE : TodayState.WALKING;
    }
}
