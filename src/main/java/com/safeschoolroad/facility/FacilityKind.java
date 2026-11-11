package com.safeschoolroad.facility;

// 핵심 지점으로 쓰는 공공데이터 시설 종류 (HANDOFF 5-3).
// 보안등은 주변 시설 정보용이며 핵심 안내 지점으로 선택하지 않는다.
public enum FacilityKind {
    CROSSWALK("횡단보도"),
    WALK_JUNCTION("갈림길"),
    SCHOOL("학교"),
    CCTV("CCTV"),
    EMERGENCY_BELL("안전비상벨"),
    CHILD_SAFETY_HOUSE("아동안전지킴이집"),
    SECURITY_LIGHT("보안등"),
    PEDESTRIAN_SIGNAL("보행신호등"),
    // 아래는 핵심 지점으로 쓰지 않고 위험요소 집계에만 쓴다
    SCHOOL_ZONE("어린이보호구역");

    private final String label;

    FacilityKind(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 핵심 지점(번호 핀)으로 쓸 수 있는 종류인지 */
    public boolean isKeyPointKind() {
        return this != WALK_JUNCTION && this != SCHOOL_ZONE && this != SECURITY_LIGHT && this != PEDESTRIAN_SIGNAL;
    }
}
