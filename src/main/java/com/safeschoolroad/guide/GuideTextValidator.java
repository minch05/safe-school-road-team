package com.safeschoolroad.guide;

import java.util.Set;
import java.util.regex.Pattern;

final class GuideTextValidator {
    private static final Pattern UNVERIFIED = Pattern.compile(
            "왼쪽|오른쪽|왼편|오른편|좌회전|우회전|좌측|우측|동쪽|서쪽|남쪽|북쪽|유턴|직진|[0-9]|[<>]|https?://|초록\\s*불|파란\\s*불|녹색\\s*불"
            + "|신호(?:등)?(?:이|가|를)\\s*(?:바뀌|기다)|지켜\\s*[주줘]|보호해\\s*주|안전(?:한|해|하|점수)|가장\\s*안전|더\\s*안전"
            + "|안심|걱정\\s*없|위험도|위험한\\s*길|점수|등급|도착했|도착이에요|도착해요|머리\\s*위|바닥에|전봇대|기둥에|작동"
            + "|(?:마지막|첫\\s*(?:번째|째)?|[두세네]\\s*번째)\\s*(?:횡단보도|CCTV|비상벨|학교)"
            + "|멈(?:춰|추)지\\s*(?:말|마|않)|확인(?:하)?지\\s*(?:말|마|않)|살피지\\s*(?:말|마|않)|보지\\s*(?:말|마|않)"
            + "|확인\\s*없이|확인\\s*안|안\\s*(?:봐|보|확인|멈)|필요(?:가|는)?\\s*없|생략"
            + "|신호(?:를|는)?\\s*무시|뛰어|달려");
    private static final Pattern PLACE = Pattern.compile(
            "([가-힣A-Za-z0-9]{1,30}(?:초등학교|중학교|고등학교|학교|역|공원|아파트|마트|편의점|병원|약국|은행|교회|성당|빌딩|상가|시장|센터|도서관|어린이집|유치원|학원|가게|식당|카페|로|길))");
    private static final Set<String> COMMON = Set.of("길", "큰길", "골목길", "찻길", "이길", "그길", "학교",
            "도로", "쪽으로", "앞으로", "옆으로", "뒤로", "밖으로", "안으로", "위로", "아래로", "따로",
            "바로", "새로", "그대로", "스스로", "서로", "절대로", "제대로", "차례로", "건너편으로", "반대로",
            "선으로", "곳으로", "방향으로", "곧바로", "길로", "큰길로", "골목길로", "구역", "지역",
            "학교로", "통학로", "통학길", "집으로", "끝으로", "처음으로", "계속");
    private static final Pattern TURN = Pattern.compile("꺾|돌아|틀어|방향을\\s*바꿔");
    private static final Pattern ALMOST = Pattern.compile("다\\s*왔|거의\\s*다");
    private static final Pattern BOTH = Pattern.compile("양쪽|양옆|좌우|양\\s*방향");

    private GuideTextValidator() { }

    static boolean valid(GuideWriter.Input point, String text) {
        String clean = text.strip();
        if (clean.isEmpty() || clean.length() > 120 || clean.contains("\n") || (!clean.endsWith("요.")
                && !clean.endsWith("요!") && !clean.endsWith("요"))) return false;
        String name = point.name() == null ? "" : point.name().strip();
        // A place is allowed only at its own point, and at most once.
        String checked = clean;
        if (!name.isEmpty()) {
            int at = checked.indexOf(name);
            if (at >= 0) checked = checked.substring(0, at) + checked.substring(at + name.length());
        }
        if (UNVERIFIED.matcher(checked).find() || checked.contains("·")) return false;
        var places = PLACE.matcher(checked);
        while (places.find()) {
            if (!COMMON.contains(places.group(1))) return false;
        }
        boolean turn = TURN.matcher(clean).find();
        if ("TURN".equals(point.reason()) != turn) return false;
        if (!point.isLast() && ALMOST.matcher(clean).find()) return false;
        return switch (point.kind()) {
            case "CROSSWALK" -> clean.contains("횡단보도") && (clean.contains("멈춰") || clean.contains("멈추"))
                    && clean.contains("신호") && BOTH.matcher(clean).find();
            case "SCHOOL" -> name.isEmpty() ? clean.contains("학교") : clean.contains(name);
            case "CCTV" -> clean.contains("CCTV") || clean.contains("카메라");
            case "EMERGENCY_BELL" -> clean.contains("비상벨");
            default -> false;
        };
    }
}
