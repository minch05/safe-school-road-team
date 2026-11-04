package com.safeschoolroad.saved;

import com.safeschoolroad.evidence.RouteEvidence.KeyPoint;

/**
 * AI 없이 코드가 정한 문장으로 만드는 기본 안내문 (FR-09 대체 문장). 개인 개발본 TemplateGuide를 옮겼다.
 * 규칙: 한 지점에 행동 하나, 식별 이름은 최대 하나, 횡단보도는 항상 멈춤·신호·양쪽 확인을 넣는다.
 * 신호 확정 자료가 없으므로 '초록불이면 건너요'처럼 신호가 있다고 가정하는 말은 쓰지 않는다.
 */
public final class TemplateGuide {
    public static final String SOURCE = "TEMPLATE";

    private TemplateGuide() { }

    /** nth: 같은 종류·이유가 이 경로에서 몇 번째인지(0부터). 같은 문장이 반복되지 않게 표현을 바꾼다. */
    public static String sentence(KeyPoint p, int nth, boolean last) {
        boolean turn = "TURN".equals(p.reason());
        String text = switch (p.kind()) {
            case "CROSSWALK" -> pick(nth,
                    "횡단보도예요. 멈춰서 신호와 양쪽을 확인하고 건너요.",
                    "횡단보도 앞에서 먼저 멈춰요. 신호를 보고 양쪽에서 차가 오는지 확인해요.",
                    "또 횡단보도가 나와요. 멈춰서 신호를 확인하고 양쪽을 한 번 더 봐요.",
                    "횡단보도에서는 꼭 멈춰요. 신호등이 있으면 신호를 지키고, 양쪽을 보고 건너요.");
            case "SCHOOL" -> turn
                    ? schoolName(p) + " 쪽에서 길을 꺾어요. 지도의 선을 보고 따라가요."
                    : schoolName(p) + " 앞을 지나요.";
            case "CCTV" -> turn
                    ? pick(nth, "CCTV가 있는 곳에서 길을 꺾어요. 지도의 선을 보고 따라가요.", "CCTV 근처에서 길을 꺾어요. 지도의 선을 따라가요.")
                    : pick(nth, "CCTV가 있는 길을 따라 걸어가요.", "이 길에 CCTV가 있어요. 그대로 걸어가요.", "CCTV를 지나 계속 걸어가요.");
            case "EMERGENCY_BELL" -> turn
                    ? pick(nth, "안전비상벨이 있는 곳에서 길을 꺾어요. 지도의 선을 보고 따라가요.", "안전비상벨 근처에서 길을 꺾어요. 지도의 선을 따라가요.")
                    : pick(nth, "안전비상벨이 있는 곳을 지나요. 위험할 때 누르는 벨이에요.", "여기에도 안전비상벨이 있어요. 위험할 때 눌러요.",
                    "안전비상벨을 지나 계속 걸어가요.");
            default -> throw new IllegalArgumentException("안내 지점으로 쓰지 않는 자료 종류입니다: " + p.kind());
        };
        return last && !"CROSSWALK".equals(p.kind()) ? text + " 거의 다 왔어요!" : text;
    }

    private static String pick(int nth, String... options) {
        return options[Math.floorMod(nth, options.length)];
    }

    private static String schoolName(KeyPoint p) {
        String n = p.name() == null ? "" : p.name().strip();
        return n.isEmpty() ? "학교" : n;
    }
}
