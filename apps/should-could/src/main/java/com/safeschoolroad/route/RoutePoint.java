package com.safeschoolroad.route;

import com.safeschoolroad.facility.FacilityKind;
import com.safeschoolroad.facility.SignalStatus;
import com.safeschoolroad.route.analysis.PointReason;
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
import jakarta.persistence.Table;

// 핵심 지점 하나 = 확정한 경로가 지나가는 공공데이터 시설 (HANDOFF 5-3, 8장).
// 시설 값을 복사해 둔다(공공데이터를 다시 적재해도 지점이 바뀌지 않게). 원본은 source + sourceId 로 가리킨다.
@Entity
@Table(name = "route_point")
public class RoutePoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_id")
    private Route route;

    // 경로상 순서 (1부터)
    private int seq;

    @Column(nullable = false, length = 40)
    private String facilitySource;

    @Column(nullable = false, length = 40)
    private String facilitySourceId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR) // MySQL ENUM 대신 글자 칸 (종류를 나중에 늘려도 되게)
    @Column(nullable = false, length = 20)
    private FacilityKind kind;

    @Column(nullable = false, length = 100)
    private String name;

    private double lat;
    private double lng;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR) // MySQL ENUM 대신 글자 칸 (종류를 나중에 늘려도 되게)
    @Column(length = 20)
    private SignalStatus signalStatus;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR) // MySQL ENUM 대신 글자 칸 (종류를 나중에 늘려도 되게)
    @Column(nullable = false, length = 10)
    private PointReason reason;

    // 확정할 때 경로선까지의 거리(m). 달라짐 확인에 쓴다(HANDOFF 5-4)
    private double distanceAtConfirm;

    @Column(nullable = false, length = 300)
    private String guideText = "";

    // 사용자가 안내문을 직접 고쳤는지 (FR-10)
    private boolean guideEdited;

    protected RoutePoint() {
    }

    public RoutePoint(Route route, int seq, String facilitySource, String facilitySourceId, FacilityKind kind,
                      String name, double lat, double lng, SignalStatus signalStatus, PointReason reason,
                      double distanceAtConfirm) {
        this.route = route;
        this.seq = seq;
        this.facilitySource = facilitySource;
        this.facilitySourceId = facilitySourceId;
        this.kind = kind;
        this.name = name;
        this.lat = lat;
        this.lng = lng;
        this.signalStatus = signalStatus;
        this.reason = reason;
        this.distanceAtConfirm = distanceAtConfirm;
    }

    public void setGuideText(String guideText) {
        this.guideText = guideText;
    }

    public void editGuideText(String guideText) {
        this.guideText = guideText;
        this.guideEdited = true;
    }

    public Long getId() {
        return id;
    }

    public Route getRoute() {
        return route;
    }

    public int getSeq() {
        return seq;
    }

    public String getFacilitySource() {
        return facilitySource;
    }

    public String getFacilitySourceId() {
        return facilitySourceId;
    }

    public FacilityKind getKind() {
        return kind;
    }

    public String getName() {
        return name;
    }

    public double getLat() {
        return lat;
    }

    public double getLng() {
        return lng;
    }

    public SignalStatus getSignalStatus() {
        return signalStatus;
    }

    public PointReason getReason() {
        return reason;
    }

    public double getDistanceAtConfirm() {
        return distanceAtConfirm;
    }

    public String getGuideText() {
        return guideText;
    }

    public boolean isGuideEdited() {
        return guideEdited;
    }
}
