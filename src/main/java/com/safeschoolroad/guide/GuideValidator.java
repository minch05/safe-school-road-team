package com.safeschoolroad.guide;

import com.safeschoolroad.facility.FacilityKind;
import com.safeschoolroad.facility.SignalStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// AI 안내문을 지점 목록과 대조한다 (FR-09). 목표는 오류 0건 — 하나라도 걸리면 다시 만든다.
//  1. 지점 수·번호가 맞는가
//  2. 목록에 없는 장소 이름(학교·역·가게·길 이름 등)을 지어내지 않았는가
//  3. 공개 도보망에서 계산한 해당 지점 방향 외의 방향·거리·시간을 지어내지 않았는가
//  4. 신호가 확실하지 않은 횡단보도에서 신호가 있다고 가정하지 않았는가 (HANDOFF 5-8)
public final class GuideValidator {

    private static final int MAX_LENGTH = 120;

    // 장소 이름처럼 보이는 낱말: ~학교, ~역, ~공원, ~로, ~길 등
    private static final Pattern PLACE_WORD = Pattern.compile(
            "([가-힣A-Za-z0-9]{1,20}(?:초등학교|중학교|고등학교|학교|역|공원|아파트|마트|편의점|병원|약국|은행|교회|성당|빌딩|상가|시장|주민센터|센터|도서관|어린이집|유치원|학원|문구점|빵집|가게|식당|카페|로|길))");

    // "~길·~로"로 끝나도 장소 이름이 아닌 흔한 말
    private static final Set<String> GENERIC = Set.of(
            "길", "큰길", "골목길", "찻길", "이길", "그길", "앞길", "옆길", "뒷길", "갈림길", "가는길", "오는길", "걷는길",
            "산책길", "인도", "보도", "차도", "학교", "횡단보도", "도로", "쪽으로", "앞으로", "옆으로", "뒤로", "밖으로",
            "안으로", "위로", "아래로", "따로", "바로", "새로", "그대로", "스스로", "서로", "절대로", "제대로", "마음대로",
            "차례로", "건너편으로", "반대로", "선으로", "곳으로", "방향으로", "쪽길", "곧바로", "길로", "큰길로",
            "골목길로", "구역", "지역", "보호구역", "어린이보호구역", "학교로", "학교길", "통학로", "통학길", "등굣길", "하굣길",
            "오른쪽으로", "왼쪽으로", "집으로", "끝으로", "처음으로", "천천히", "조심스레");

    private static final Pattern DIRECTION = Pattern.compile("왼쪽|오른쪽|좌회전|우회전|좌측|우측|직진하|[0-9]+\\s*(m|미터|km|킬로|분|초)");
    // 시설이 실제로 작동하는지 모르므로 "지켜 준다", "안전해요"처럼 안전을 약속하는 말은 쓰지 않는다
    private static final Pattern SAFETY_PROMISE = Pattern.compile("지켜\\s?주|지켜\\s?줘|보호해\\s?주|안전한 길이에요|안전해요|걱정 없|안심하");
    // 도착 버튼을 누르기 전에 도착했다고 말하지 않는다. "다 왔어요"는 마지막 지점에서만
    private static final Pattern ARRIVED = Pattern.compile("도착했|도착이에요|도착해요|도착이야");
    private static final Pattern ALMOST = Pattern.compile("다 왔|거의 다");
    // 꺾는 곳에서는 꺾으라고 해야 하고, 꺾지 않는 곳에서 꺾으라고 하면 아이가 길을 잘못 든다.
    // 50개 경로 시험(2026-10-10): 꺾음 지점에서 꺾는 말이 빠진 비율 qwen3.8-27b 20%, gpt-oss-120b 33%
    private static final Pattern TURN_WORD = Pattern.compile("꺾|돌아|틀어|방향을 바꿔");
    // 지점 목록에 없는 위치 묘사 (머리 위, 바닥 등)
    private static final Pattern MADE_UP_POSITION = Pattern.compile("머리 위|바닥에|전봇대|기둥에 달린");
    // 안내 지점은 전체 시설 목록이 아니므로 경로 전체의 첫/몇 번째/마지막 시설을 단정하지 않는다.
    private static final Pattern FACILITY_ORDER = Pattern.compile(
            "(?:마지막|첫\\s*(?:번째|째)?|[두세네]\\s*(?:번째|째)|[0-9]+\\s*번째)\\s*(?:횡단보도|건널목|CCTV|비상벨|지킴이집)");
    private static final Pattern GRAMMAR_SLIP = Pattern.compile("에서 여기서|에서\\s*여기에서");
    private static final Pattern BOTH_SIDES = Pattern.compile("양쪽|양옆|좌우|양\\s*방향");
    private static final Pattern SIGNAL_ASSUMED = Pattern.compile("초록불|파란불|녹색불|초록 불|파란 불|신호가 바뀌면|신호등이 바뀌면|신호를 기다려");

    private GuideValidator() {
    }

    public static List<String> crossingErrors(String text, SignalStatus signalStatus) {
        List<String> errors = new ArrayList<>();
        if (signalStatus != SignalStatus.YES
                && SIGNAL_ASSUMED.matcher(text).find()) {
            errors.add("신호가 확실하지 않은 횡단보도인데 신호가 있다고 가정했어요.");
        }
        if (!(text.contains("멈춰") || text.contains("멈추"))) {
            errors.add("횡단보도 문장에 '멈춰서 확인'이 없어요.");
        }
        if (!text.contains("신호")) {
            errors.add("횡단보도 문장에 '신호와 양쪽을 확인'이 없어요. 신호가 있는 곳일 수 있어요.");
        }
        if (!BOTH_SIDES.matcher(text).find()) {
            errors.add("횡단보도 문장에 양쪽 확인 안내가 없어요. 신호만 보지 말고 양쪽에서 오는 차도 확인하도록 써 주세요.");
        }
        return errors;
    }

    /** 문제 목록. 비어 있으면 통과 */
    public static List<String> validate(List<GuidePoint> points, Map<Integer, String> texts) {
        List<String> errors = new ArrayList<>();
        if (texts.size() != points.size()) {
            errors.add("지점은 " + points.size() + "개인데 문장은 " + texts.size() + "개예요.");
        }
        List<String> allowed = points.stream().map(GuidePoint::name).filter(n -> n != null && !n.isBlank())
                .flatMap(n -> List.of(n.split("\\s*·\\s*")).stream()).toList();
        for (GuidePoint p : points) {
            String text = texts.get(p.seq());
            if (text == null || text.isBlank()) {
                errors.add(p.seq() + "번 문장이 없어요.");
                continue;
            }
            if (text.length() > MAX_LENGTH) {
                errors.add(p.seq() + "번 문장이 너무 길어요.");
            }
            // 옆 지점의 도로명은 이름 없는 횡단보도의 이름이나 위치 근거가 아니다.
            boolean unnamedCrosswalk = p.kind() == FacilityKind.CROSSWALK
                    && (p.name() == null || p.name().isBlank());
            List<String> placeNames = unnamedCrosswalk ? List.of() : allowed;
            Matcher m = PLACE_WORD.matcher(text);
            while (m.find()) {
                String word = m.group(1);
                if (!isAllowedPlace(word, placeNames) && !isAllowedPlace(stripParticle(word), placeNames)) {
                    if (unnamedCrosswalk) {
                        errors.add(p.seq() + "번은 이름 없는 횡단보도예요. 다른 지점의 장소·길 이름 '" + word
                                + "'을 붙이지 말고 횡단보도라고만 안내해 주세요.");
                        continue;
                    }
                    errors.add(p.seq() + "번 문장의 '" + word + "'은(는) 지점 목록에 없는 장소예요.");
                }
            }
            if (text.contains("·") || text.contains("종류:") || text.contains("가로변")) {
                errors.add(p.seq() + "번 문장에 목록 표기(·, 가로변 등)가 그대로 들어갔어요. 아이가 알아듣는 말로 써 주세요.");
            }
            String directionChecked = p.directionWord().isEmpty() ? text : text.replace(p.directionWord(), "");
            if (DIRECTION.matcher(directionChecked).find()) {
                errors.add(p.seq() + "번 문장에 방향이나 거리·시간이 있어요. 지점 목록에는 그런 정보가 없어요.");
            }
            if (!p.directionWord().isEmpty() && !text.contains(p.directionWord())) {
                errors.add(p.seq() + "번 갈림길의 확인된 방향이 누락됐어요.");
            }
            if (p.kind() == FacilityKind.CROSSWALK) {
                crossingErrors(text, p.signalStatus()).forEach(error -> errors.add(p.seq() + "번 " + error));
            }
            if (SAFETY_PROMISE.matcher(text).find()) {
                errors.add(p.seq() + "번 문장이 안전을 약속해요(지켜 준다 등). 시설이 작동하는지는 확인되지 않았어요.");
            }
            if (ARRIVED.matcher(text).find()) {
                errors.add(p.seq() + "번 문장이 도착했다고 말해요. 도착은 아이가 직접 확인하고 버튼을 눌러요.");
            }
            boolean saysTurn = TURN_WORD.matcher(text).find();
            if (p.reason() == com.safeschoolroad.route.analysis.PointReason.TURN && !saysTurn) {
                errors.add(p.seq() + "번은 길을 꺾는 곳이에요. \"여기서 길을 꺾어요\"를 넣어 주세요.");
            }
            if (p.reason() != null && p.reason() != com.safeschoolroad.route.analysis.PointReason.TURN && saysTurn) {
                errors.add(p.seq() + "번은 길을 꺾는 곳이 아니에요. 꺾으라는 말을 빼 주세요.");
            }
            if (!p.last() && ALMOST.matcher(text).find()) {
                errors.add(p.seq() + "번은 마지막 지점이 아닌데 다 왔다고 말해요.");
            }
            if (!mentionsLandmark(p, text)) {
                errors.add(p.seq() + "번 문장에 이 지점의 시설(" + p.kind().getLabel() + ")이나 이름이 없어요. 아이가 어디인지 알아볼 수 있게 써 주세요.");
            }
            // 같은 경로 안에서 똑같은 문장을 다시 쓰면 아이에게 반복으로 보인다 (10-10 시험: 경로 안 반복 24건)
            for (GuidePoint q : points) {
                if (q.seq() >= p.seq()) {
                    break;
                }
                String other = texts.get(q.seq());
                if (other != null && sameSentence(other, text)) {
                    errors.add(p.seq() + "번 문장이 " + q.seq() + "번 문장과 똑같아요. 지점마다 다르게 써 주세요.");
                    break;
                }
            }
            if (FACILITY_ORDER.matcher(text).find()) {
                errors.add(p.seq() + "번 문장이 첫 번째·마지막 시설이라고 단정해요. 안내 목록에는 모든 시설이 포함되지 않으니 순서를 붙이지 마세요.");
            }
            if (GRAMMAR_SLIP.matcher(text).find()) {
                errors.add(p.seq() + "번 문장의 \"~에서 여기서\"처럼 어색한 표현을 고쳐 주세요.");
            }
            if (MADE_UP_POSITION.matcher(text).find()) {
                errors.add(p.seq() + "번 문장에 지점 목록에 없는 위치 묘사가 있어요.");
            }
        }
        return errors;
    }

    // 띄어쓰기·문장부호만 다른 것도 같은 문장으로 본다
    static boolean sameSentence(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    private static String normalize(String s) {
        return s.replaceAll("[\\s.!?~,]", "");
    }

    // 품질 기준: 아이가 어느 지점인지 알아볼 수 있게, 그 지점의 시설 종류나 이름(학교·길 이름)이 문장에 있어야 한다.
    // 50개 경로 시험(2026-10-10)에서 가벼운 모델은 "길을 따라 바르게 걸어요"처럼 지점 정보가 없는 문장이 34% 나왔다.
    static boolean mentionsLandmark(GuidePoint p, String text) {
        String kindWord = switch (p.kind()) {
            case WALK_JUNCTION -> "갈림길";
            case CROSSWALK -> "횡단보도";
            case SCHOOL -> "학교";
            case CCTV -> "CCTV";
            case EMERGENCY_BELL -> "비상벨";
            case CHILD_SAFETY_HOUSE -> "지킴이집";
            case SECURITY_LIGHT -> "보안등";
            case SCHOOL_ZONE -> "보호구역";
            case PEDESTRIAN_SIGNAL -> "신호";
        };
        if (text.contains(kindWord) || (p.kind() == FacilityKind.CCTV && text.contains("카메라"))) {
            return true;
        }
        for (String part : p.name().split("\\s*·\\s*")) {
            if (part.length() > 1 && !part.equals("가로변") && text.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAllowedPlace(String word, List<String> allowed) {
        if (GENERIC.contains(word) || word.endsWith("횡단보도") || word.length() < 2) {
            return true;
        }
        for (String name : allowed) {
            String compact = name.replace(" ", "");
            if (compact.contains(word) || word.contains(compact)) {
                return true;
            }
        }
        return false;
    }

    // "큰길로", "면일초등학교로" 같은 끝의 "(으)로"는 떼고 다시 본다
    private static String stripParticle(String word) {
        for (String particle : List.of("으로", "로")) {
            if (word.length() > particle.length() + 1 && word.endsWith(particle)) {
                return word.substring(0, word.length() - particle.length());
            }
        }
        return word;
    }
}
