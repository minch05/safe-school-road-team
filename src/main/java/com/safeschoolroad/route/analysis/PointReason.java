package com.safeschoolroad.route.analysis;

// 핵심 지점을 넣은 이유 (HANDOFF 5-3 "지점 고르는 규칙")
public enum PointReason {
    CROSSING("건넘"), // TMAP이 "횡단보도를 건넌다"고 한 곳
    TURN("꺾음"),     // 길을 꺾는 곳 근처 시설
    GAP("간격");      // 지점 사이가 200m 넘게 비어 채운 시설

    private final String label;

    PointReason(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
