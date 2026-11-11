package com.safeschoolroad.trip;

import com.safeschoolroad.member.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

// 출발·도착 기록 (HANDOFF 8장). 위치는 없다. 아이 폰이 보내는 것은 "출발함/도착함"과 예상 소요 시간뿐이다.
// 경로를 지워도 기록은 남도록 경로는 번호와 이름만 복사해 둔다.
@Entity
@Table(name = "trip", indexes = @Index(name = "idx_trip_child_started", columnList = "child_id, startedAt"))
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "child_id")
    private Member child;

    private Long routeId;

    // 위치가 아닌 출발 요청 식별자. 응답 유실 뒤 재시도해도 같은 출발로 처리한다.
    @Column(name = "start_request_id", length = 36, unique = true)
    private String startRequestId;

    @Column(nullable = false, length = 30)
    private String routeName;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    // 출발 이벤트에 담겨 온 예상 소요 시간(초). TMAP 응답이라 하루가 지나면 0으로 지운다(HANDOFF 5-6, TripService)
    private int expectedSeconds;

    private LocalDateTime arrivedAt;

    // 목적지 50m 자동 감지로 도착했는지 (버튼이 아니라)
    private boolean arrivedAuto;

    // 도착 지연 알림을 보낸 시각 (한 번만 보낸다)
    private LocalDateTime delayAlertSentAt;

    protected Trip() {
    }

    public Trip(Member child, Long routeId, String routeName, int expectedSeconds) {
        this(child, routeId, routeName, expectedSeconds, null);
    }

    public Trip(Member child, Long routeId, String routeName, int expectedSeconds, String startRequestId) {
        this.startRequestId = startRequestId;
        this.child = child;
        this.routeId = routeId;
        this.routeName = routeName;
        this.expectedSeconds = expectedSeconds;
        this.startedAt = LocalDateTime.now();
    }

    /** 출발 시각 + 예상 소요 시간 */
    public LocalDateTime expectedArrival() {
        return startedAt.plusSeconds(expectedSeconds);
    }

    public void arrive(boolean auto) {
        if (arrivedAt == null) {
            arrivedAt = LocalDateTime.now();
            arrivedAuto = auto;
        }
    }

    public void markDelayAlertSent() {
        delayAlertSentAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Member getChild() {
        return child;
    }

    public Long getRouteId() {
        return routeId;
    }

    public String getRouteName() {
        return routeName;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public int getExpectedSeconds() {
        return expectedSeconds;
    }

    public LocalDateTime getArrivedAt() {
        return arrivedAt;
    }

    public boolean isArrivedAuto() {
        return arrivedAuto;
    }

    public LocalDateTime getDelayAlertSentAt() {
        return delayAlertSentAt;
    }
}
