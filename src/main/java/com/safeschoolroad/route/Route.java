package com.safeschoolroad.route;

import com.safeschoolroad.member.Member;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// 저장한 경로. 경로선(TMAP 응답)은 저장하지 않고, 볼 때마다 경유지·탐색 옵션으로 다시 받는다(HANDOFF 5-4).
// 저장하는 것은 사용자가 고른 출발지·도착지 좌표, 탐색 옵션, 공공데이터 핵심 지점뿐이다.
@Entity
@Table(name = "route")
public class Route {

    public enum ChangeStatus {
        UNCHECKED, // 아직 다시 받아 본 적 없음
        SAME,      // 확정 때와 같음
        CHANGED    // 달라졌을 수 있음 (HANDOFF 5-4 기준)
    }

    // 안내문 말투 (FR-24). 저학년은 더 짧고 쉬운 말
    public enum Tone {
        LOWER("저학년"),
        UPPER("고학년");

        private final String label;

        Tone(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 이 경로를 걷는 사람 (보호자가 대신 등록하면 아이)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id")
    private Member owner;

    // 등록한 사람
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "registered_by_id")
    private Member registeredBy;

    @Column(nullable = false, length = 30)
    private String name;

    @Column(nullable = false, length = 30)
    private String startName;
    private double startLat;
    private double startLng;

    @Column(nullable = false, length = 30)
    private String endName;
    private double endLat;
    private double endLng;

    // 확정할 때 쓴 TMAP 탐색 옵션 (0 추천, 4 대로우선, 10 최단)
    private int searchOption;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR) // MySQL ENUM 대신 글자 칸 (종류를 나중에 늘려도 되게)
    @Column(nullable = false, length = 10)
    private ChangeStatus changeStatus = ChangeStatus.UNCHECKED;

    private LocalDateTime changeCheckedAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR) // MySQL ENUM 대신 글자 칸 (종류를 나중에 늘려도 되게)
    @Column(nullable = false, length = 10)
    private Tone tone = Tone.LOWER;

    // 안내문을 만든 방법과 시도 횟수 (FR-09 검증 기록, FR-24 품질 기록)
    @Column(nullable = false, length = 10)
    private String guideSource = "TEMPLATE";
    private int guideAttempts;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "route", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seq asc")
    private List<RoutePoint> points = new ArrayList<>();

    protected Route() {
    }

    public Route(Member owner, Member registeredBy, String name,
                 String startName, double startLat, double startLng,
                 String endName, double endLat, double endLng, int searchOption, Tone tone) {
        this.owner = owner;
        this.registeredBy = registeredBy;
        this.name = name;
        this.startName = startName;
        this.startLat = startLat;
        this.startLng = startLng;
        this.endName = endName;
        this.endLat = endLat;
        this.endLng = endLng;
        this.searchOption = searchOption;
        this.tone = tone;
        this.createdAt = LocalDateTime.now();
    }

    public void addPoint(RoutePoint point) {
        points.add(point);
    }

    public void reassignRegistrantToOwner() {
        this.registeredBy = this.owner;
    }

    public void recordGuide(String source, int attempts) {
        this.guideSource = source;
        this.guideAttempts = attempts;
    }

    public void recordChangeCheck(boolean changed) {
        this.changeStatus = changed ? ChangeStatus.CHANGED : ChangeStatus.SAME;
        this.changeCheckedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Member getOwner() {
        return owner;
    }

    public Member getRegisteredBy() {
        return registeredBy;
    }

    public String getName() {
        return name;
    }

    public String getStartName() {
        return startName;
    }

    public double getStartLat() {
        return startLat;
    }

    public double getStartLng() {
        return startLng;
    }

    public String getEndName() {
        return endName;
    }

    public double getEndLat() {
        return endLat;
    }

    public double getEndLng() {
        return endLng;
    }

    public int getSearchOption() {
        return searchOption;
    }

    public ChangeStatus getChangeStatus() {
        return changeStatus;
    }

    public LocalDateTime getChangeCheckedAt() {
        return changeCheckedAt;
    }

    public Tone getTone() {
        return tone;
    }

    public String getGuideSource() {
        return guideSource;
    }

    public int getGuideAttempts() {
        return guideAttempts;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public List<RoutePoint> getPoints() {
        return points;
    }
}
