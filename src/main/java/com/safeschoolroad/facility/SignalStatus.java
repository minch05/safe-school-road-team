package com.safeschoolroad.facility;

// 횡단보도 신호 상태. 서울은 신호 유무 데이터가 없어 보행등 위치로 추정한다(HANDOFF 6장).
// "없음"은 두지 않는다. 데이터가 없으면 "안전/없음"이 아니라 "미확인"이다.
public enum SignalStatus {
    YES("신호 있음"),           // 정답 데이터가 있는 곳 (예: 동작구 표준데이터). 아직 적재하지 않음
    ESTIMATED("신호 있음 추정"), // 보행등이 10m 안에 있음
    UNKNOWN("신호 미확인");

    private final String label;

    SignalStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
