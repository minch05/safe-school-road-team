package com.safeschoolroad.guide;

import com.safeschoolroad.facility.FacilityKind;
import com.safeschoolroad.facility.SignalStatus;
import com.safeschoolroad.route.analysis.PointReason;

// AI 없이 코드가 정해진 문장으로 만드는 안내문. LLM 키가 없거나, AI 문장이 검증을 끝까지 통과하지 못할 때 쓴다.
// 횡단보도는 신호가 확실한 곳(YES)이 아니면 초록불을 말하지 않고 "멈춤·신호·양쪽 확인"만 쓴다(HANDOFF 5-8).
// 같은 종류가 다시 나오면 표현을 바꿔 같은 문장이 반복되지 않게 한다(10-10).
public final class TemplateGuide {

    private TemplateGuide() {
    }

    public static String sentence(GuidePoint p) {
        return sentence(p, 0);
    }

    /**
     * @param nth 같은 종류·이유의 지점이 이 경로에서 몇 번째로 나왔는지(0부터). 같은 문장이 반복되지 않게 표현을 바꾼다.
     */
    public static String sentence(GuidePoint p, int nth) {
        boolean turn = p.reason() == PointReason.TURN;
        String text = switch (p.kind()) {
            case WALK_JUNCTION -> p.directionWord().isEmpty()
                    ? (turn ? "갈림길에서 길을 꺾어요. 멈춰서 지도의 선을 확인해요." : "갈림길이에요. 멈춰서 지도의 선을 확인해요.")
                    : pick(nth, "갈림길에서 " + p.directionWord() + "으로 꺾어요. 차가 오는지 살펴요.",
                    "갈림길이에요. 차를 살피고 " + p.directionWord() + "으로 길을 꺾어요.",
                    "앞의 갈림길에서 차를 확인하고 " + p.directionWord() + "으로 꺾어요.");
            case CROSSWALK -> p.signalStatus() == SignalStatus.YES
                    ? pick(nth,
                    "횡단보도예요. 멈춰서 신호를 보고, 초록불이 켜지면 양쪽을 확인하고 건너요.",
                    "횡단보도 앞에서 멈춰요. 초록불 신호가 켜져도 양쪽을 한 번 더 보고 건너요.",
                    "또 횡단보도가 나와요. 멈춰서 신호를 기다리고, 초록불이 켜지면 양쪽을 살피고 건너요.")
                    : pick(nth,
                    "횡단보도예요. 멈춰서 신호와 양쪽을 확인하고 건너요.",
                    "횡단보도 앞에서 먼저 멈춰요. 신호를 보고 양쪽에서 차가 오는지 확인해요.",
                    "또 횡단보도가 나와요. 멈춰서 신호를 확인하고 양쪽을 한 번 더 봐요.",
                    "횡단보도에서는 꼭 멈춰요. 신호등이 있으면 신호를 지키고, 양쪽을 보고 건너요.");
            case SCHOOL -> turn
                    ? name(p) + " 쪽에서 길을 꺾어요. 지도의 선을 보고 따라가요."
                    : name(p) + " 앞을 지나요.";
            case CCTV -> turn
                    ? pick(nth, "CCTV가 있는 곳에서 길을 꺾어요. 지도의 선을 보고 따라가요.",
                    "CCTV 근처에서 길을 꺾어요. 지도의 선을 따라가요.")
                    : pick(nth, "CCTV가 있는 " + withObjectMarker(road(p)) + " 따라 걸어가요.",
                    road(p) + "에 CCTV가 있어요. 그대로 걸어가요.",
                    "CCTV를 지나 계속 걸어가요.");
            case EMERGENCY_BELL -> turn
                    ? pick(nth, "안전비상벨이 있는 곳에서 길을 꺾어요. 지도의 선을 보고 따라가요.",
                    "안전비상벨 근처에서 길을 꺾어요. 지도의 선을 따라가요.")
                    : pick(nth, "안전비상벨이 있는 곳을 지나요. 위험할 때 누르는 벨이에요.",
                    "여기에도 안전비상벨이 있어요. 위험할 때 눌러요.",
                    "안전비상벨을 지나 계속 걸어가요.");
            case CHILD_SAFETY_HOUSE -> turn
                    ? "아동안전지킴이집이 있는 곳에서 길을 꺾어요. 지도의 선을 보고 따라가요."
                    : pick(nth, "아동안전지킴이집을 지나요. 도움이 필요하면 어른에게 부탁해요.",
                    "아동안전지킴이집이 있어요. 도움이 필요하면 들어가서 말해요.");
            case SECURITY_LIGHT -> turn
                    ? "보안등이 있는 곳에서 길을 꺾어요. 지도의 선을 보고 따라가요."
                    : pick(nth, "보안등이 있는 길을 지나요.", "보안등이 있는 길을 계속 걸어가요.");
            case PEDESTRIAN_SIGNAL -> throw new IllegalArgumentException("보행신호등 위치는 단독 안내 지점으로 사용하지 않습니다.");
            case SCHOOL_ZONE -> turn
                    ? "어린이보호구역에서 길을 꺾어요. 차를 조심해요."
                    : pick(nth, "어린이보호구역이에요. 차를 조심해요.", "어린이보호구역을 지나요. 차가 오는지 잘 봐요.");
        };
        return p.last() && p.kind() != FacilityKind.CROSSWALK ? text + " 거의 다 왔어요!" : text;
    }

    private static String pick(int nth, String... options) {
        return options[Math.floorMod(nth, options.length)];
    }

    private static String name(GuidePoint p) {
        return p.name().isBlank() ? "학교" : p.name();
    }

    // 받침이 있으면 "을", 없으면 "를" (예: 면목로를, 사가정로50길을)
    static String withObjectMarker(String word) {
        char last = word.charAt(word.length() - 1);
        boolean hangul = last >= 0xAC00 && last <= 0xD7A3;
        boolean hasFinal = hangul ? (last - 0xAC00) % 28 != 0 : Character.isDigit(last) && "013678".indexOf(last) >= 0;
        return word + (hasFinal ? "을" : "를");
    }

    // 이름에서 도로 이름만 (비상벨은 "가로변 · 용마산로" 모양)
    private static String road(GuidePoint p) {
        String n = p.name();
        int dot = n.lastIndexOf('·');
        String road = (dot >= 0 ? n.substring(dot + 1) : n).strip();
        return road.isBlank() ? "길" : road;
    }
}
