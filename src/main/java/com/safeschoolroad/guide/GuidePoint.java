package com.safeschoolroad.guide;

import com.safeschoolroad.facility.FacilityKind;
import com.safeschoolroad.facility.SignalStatus;
import com.safeschoolroad.route.analysis.PointReason;

// 안내문 재료가 되는 지점 하나. LLM에는 이 값만 넘긴다(별명·주소·출발지 이름은 넘기지 않음, HANDOFF 5-8).
// name 은 공공데이터의 정해진 항목으로 만든 이름(도로명·학교명)이다.
public record GuidePoint(int seq, FacilityKind kind, String name, SignalStatus signalStatus, PointReason reason,
                         boolean last, String turnDirection) {
    public GuidePoint(int seq, FacilityKind kind, String name, SignalStatus signalStatus, PointReason reason, boolean last) {
        this(seq, kind, name, signalStatus, reason, last, "");
    }
    public String directionWord() {
        if (kind != FacilityKind.WALK_JUNCTION || reason != PointReason.TURN) return "";
        return "LEFT".equals(turnDirection) ? "왼쪽" : "RIGHT".equals(turnDirection) ? "오른쪽" : "";
    }
}
