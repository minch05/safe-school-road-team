package com.safeschoolroad.route;

import com.safeschoolroad.facility.FacilityKind;
import com.safeschoolroad.facility.SignalStatus;
import com.safeschoolroad.route.analysis.PointReason;

import java.io.Serializable;
import java.util.List;

// 경로 확정 화면(R-04)에서 "저장하기"를 누르기 전까지 세션에 잠깐 들고 있는 값.
// 공공데이터 지점과 사용자가 고른 좌표만 담는다(TMAP 경로선은 담지 않음).
public record RouteDraft(
        long ownerId,
        Place start,
        Place end,
        int option,
        Route.Tone tone,
        Long replaceRouteId,   // 다시 등록이면 대신할 경로
        List<Point> points,
        String guideSource,
        int guideAttempts,
        List<String> guideErrors,
        long guideLogId) implements Serializable {

    public record Place(String name, double lat, double lng) implements Serializable {
    }

    public record Point(int seq, String source, String sourceId, FacilityKind kind, String name, double lat,
                        double lng, SignalStatus signalStatus, PointReason reason, double distanceToLine,
                        String guideText) implements Serializable {
    }
}
