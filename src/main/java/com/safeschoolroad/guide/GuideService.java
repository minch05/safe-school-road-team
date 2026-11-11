package com.safeschoolroad.guide;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.facility.SignalStatus;
import com.safeschoolroad.route.Route;
import com.safeschoolroad.route.analysis.PointReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 안내문 만들기 (FR-09). 지점 목록을 AI가 문장으로 바꾸고, 코드가 지점 목록과 대조해 검증한다.
// 검증에 걸리면 무엇이 틀렸는지 알려 주고 다시 만든다(최대 3번). 그래도 안 되면 정해진 문장(TemplateGuide)을 쓴다.
@Service
public class GuideService {

    private static final Logger log = LoggerFactory.getLogger(GuideService.class);
    static final int MAX_ATTEMPTS = 3;

    private final ObjectMapper objectMapper;
    private final LlmModels models;

    @org.springframework.beans.factory.annotation.Autowired
    public GuideService(ObjectMapper objectMapper, LlmModels models) {
        this.objectMapper = objectMapper;
        this.models = models;
    }

    /** 시험용: 넘겨준 모델 하나 (없으면 AI 없음) */
    public GuideService(ObjectProvider<ChatModel> chatModel, ObjectMapper objectMapper) {
        this(objectMapper, new LlmModels(chatModel.getIfAvailable() == null ? List.of()
                : List.of(new LlmModels.Target("test", "test-model", chatModel.getIfAvailable()))));
    }

    /**
     * @param texts    지점 번호 → 문장
     * @param source   "AI" 또는 "TEMPLATE"
     * @param attempts AI를 부른 횟수 (0이면 AI를 쓰지 않음)
     * @param errors   시도마다 걸린 검증 문제 (FR-24 품질 기록용)
     * @param model    마지막으로 쓴 모델 (AI를 안 썼으면 "-")
     */
    public record GuideResult(Map<Integer, String> texts, String source, int attempts, List<String> errors, String model) {
    }

    public boolean isAiAvailable() {
        return models.isAvailable();
    }

    public GuideResult write(List<GuidePoint> points, Route.Tone tone) {
        if (!models.isAvailable() || points.isEmpty()) {
            return new GuideResult(template(points), "TEMPLATE", 0, List.of(), "-");
        }
        List<String> allErrors = new ArrayList<>();
        List<String> lastErrors = List.of();
        String used = "-";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String reply;
            try {
                // 한도에 닿은 모델은 건너뛰고 다음 모델로 (LlmModels)
                LlmModels.Reply r = models.call(systemPrompt(tone), userPrompt(points, lastErrors));
                reply = r.content();
                used = r.model();
            } catch (Exception e) {
                // 모델 오류(키 오류, 시간 초과, 모든 모델 한도 등)는 다시 해도 같을 가능성이 높아 바로 정해진 문장으로 간다
                log.warn("AI 안내문 생성 실패: {}", e.getMessage());
                allErrors.add(attempt + "회: AI 호출 실패 (" + (LlmModels.isQuota(e) ? "모든 모델 하루 한도" : e.getClass().getSimpleName()) + ")");
                return new GuideResult(template(points), "TEMPLATE", attempt, allErrors, used);
            }
            try {
                Map<Integer, String> texts = parse(reply);
                lastErrors = GuideValidator.validate(points, texts);
                if (lastErrors.isEmpty()) {
                    return new GuideResult(texts, "AI", attempt, allErrors, used);
                }
            } catch (Exception e) {
                lastErrors = List.of("답이 JSON 배열 모양이 아니에요. JSON 배열만 써 주세요.");
            }
            int n = attempt;
            allErrors.addAll(lastErrors.stream().map(e -> n + "회: " + e).toList());
            log.info("안내문 검증 실패 {}회: {}", attempt, lastErrors);
        }
        return new GuideResult(template(points), "TEMPLATE", MAX_ATTEMPTS, allErrors, used);
    }

    static Map<Integer, String> template(List<GuidePoint> points) {
        Map<Integer, String> texts = new LinkedHashMap<>();
        // 같은 종류·이유가 다시 나오면 다른 문장을 고른다 (경로 안 반복 줄이기)
        Map<String, Integer> seen = new java.util.HashMap<>();
        points.forEach(p -> {
            int nth = seen.merge(p.kind() + ":" + p.reason() + ":" + p.signalStatus(), 1, Integer::sum) - 1;
            texts.put(p.seq(), TemplateGuide.sentence(p, nth));
        });
        return texts;
    }

    private static String systemPrompt(Route.Tone tone) {
        String style = tone == Route.Tone.LOWER
                ? "초등학교 1~3학년이 알아듣게, 한 문장에 40자 안쪽으로 아주 쉽고 짧게 써요."
                : "초등학교 4~6학년에게 말하듯, 한두 문장에 60자 안쪽으로 써요.";
        return """
                당신은 초등학생이 혼자 걸어서 통학할 때 휴대폰 화면에 뜨는 길 안내 문장을 쓰는 사람입니다.
                주어진 지점마다 아이가 그 지점에 가까워졌을 때 볼 문장을 하나씩 씁니다.
                %s
                반드시 지킬 것:
                - 지점 목록에 있는 학교 이름·길 이름만 씁니다. 목록에 없는 가게·건물·학교·역·길 이름을 지어내지 않습니다.
                - 이름이 없는 횡단보도에는 다른 지점의 길·학교·장소 이름을 붙이지 않습니다.
                  옆 지점에 "신길로"가 있어도 "신길로 횡단보도"라고 하지 말고 "횡단보도"라고만 안내합니다.
                - "설치 장소"는 시설이 어디에 있는지 설명하는 말입니다(예: 길가에 있는 비상벨). 이름처럼 쓰지 않습니다.
                - 가운뎃점(·)이나 "종류:" 같은 목록 표기를 문장에 옮기지 않습니다.
                - 방향은 해당 지점에 "공개 도보망에서 계산한 방향"이 있을 때 그 단어만 씁니다. 그 외 방향, 거리(m), 시간(분)은 쓰지 않습니다.
                - "꺾음" 지점은 반드시 꺾으라고 말합니다. 방향 정보가 없으면 "CCTV가 있는 곳에서 길을 꺾어요. 지도의 선을 보고 따라가요"처럼 씁니다.
                - "꺾음"이 아닌 지점에서는 꺾으라는 말을 쓰지 않습니다(아이가 길을 잘못 들어요).
                - 횡단보도는 신호 상태가 "신호 있음"이 아니면 초록불·파란불·신호가 바뀐다는 말을 쓰지 않고,
                  "횡단보도 앞에서 멈춰서 신호와 양쪽을 확인하고 건너요"처럼 신호가 있든 없든 안전한 문장을 씁니다.
                - 횡단보도 문장에는 꼭 "횡단보도", "멈춰", "신호", "양쪽 확인"이 들어갑니다. 신호만 확인하거나 주변을 본다는 말로 양쪽 확인을 생략하지 않습니다. 예: "횡단보도 앞에서 멈춰서 신호와 양쪽을 확인하고 건너요"
                - "마지막 횡단보도", "첫 번째 비상벨"처럼 시설 순서를 단정하지 않습니다. 안내 지점 목록에는 모든 시설이 포함되어 있지 않습니다.
                - CCTV·비상벨이 아이를 "지켜 준다", "안전하다"고 말하지 않습니다. 작동하는지 확인되지 않은 시설이에요.
                - "도착했어요"처럼 도착을 말하지 않습니다. 마지막 지점에서만 "거의 다 왔어요"라고 할 수 있어요.
                - 머리 위·기둥 같은, 목록에 없는 위치 묘사를 하지 않습니다.
                - 문장마다 그 지점의 시설(CCTV·비상벨·횡단보도·학교 이름 등)을 넣어서, 아이가 "아, 여기구나" 하고 알아볼 수 있게 씁니다.
                - 같은 경로 안에서 똑같은 문장을 두 번 쓰지 않습니다. 같은 종류가 다시 나오면 표현과 문장 순서를 바꿉니다.
                  횡단보도가 여러 번 나오면 아래처럼 지킬 내용(멈춤·신호·양쪽 확인)은 같게, 말은 다르게 씁니다.
                  표현을 바꿀 때도 "두 번째 횡단보도", "마지막 횡단보도"처럼 순서를 붙이지 않고 "또", "다시", "이번에도"를 씁니다.
                  예: "횡단보도 앞에서 멈춰서 신호와 양쪽을 확인하고 건너요"
                      "횡단보도예요. 먼저 멈춰서 신호를 보고, 양쪽에서 차가 오는지 살펴요"
                      "또 횡단보도가 나와요. 멈춰서 신호를 확인하고 양쪽을 한 번 더 봐요"
                      "횡단보도에서는 꼭 멈춰요. 신호등이 있으면 신호를 지키고, 양쪽을 보고 건너요"
                - 저학년은 짧고 쉬운 말로, 고학년은 왜 그렇게 하는지 이유를 조금 덧붙여 씁니다(예: 차가 갑자기 나올 수 있어요).
                - 걸으면서 화면을 보라고 하지 않습니다. 반말이 아닌 다정한 "~해요" 말투로 씁니다.
                - 답은 JSON 배열만 씁니다. 예: [{"seq":1,"text":"..."},{"seq":2,"text":"..."}]
                """.formatted(style);
    }

    private static String userPrompt(List<GuidePoint> points, List<String> previousErrors) {
        StringBuilder sb = new StringBuilder("지점 목록 (경로 순서):\n");
        for (GuidePoint p : points) {
            sb.append(p.seq()).append(". 종류: ").append(p.kind().getLabel());
            describeName(sb, p);
            if (p.signalStatus() != null) {
                sb.append(" / 신호 상태: ").append(p.signalStatus() == SignalStatus.YES ? "신호 있음"
                        : p.signalStatus() == SignalStatus.ESTIMATED ? "신호 있음 추정(확실하지 않음)" : "미확인");
            }
            sb.append(" / 넣은 이유: ").append(reasonText(p.reason()));
            if (!p.directionWord().isEmpty()) sb.append(" / 공개 도보망에서 계산한 방향: ").append(p.directionWord());
            if (p.last()) {
                sb.append(" / 마지막 지점");
            }
            sb.append('\n');
        }
        if (!previousErrors.isEmpty()) {
            sb.append("\n지난번 답에서 고쳐야 할 점:\n");
            previousErrors.forEach(e -> sb.append("- ").append(e).append('\n'));
        }
        return sb.toString();
    }

    // 공공데이터 이름을 아이 문장에 쓰기 좋은 항목으로 나눠 준다.
    // 비상벨 이름은 "가로변 · 면목로"(설치 장소 유형 · 길 이름) 모양이라 그대로 주면 AI가 "가로변 · 면목로예요"처럼 옮겨 쓴다.
    private static final Map<String, String> PLACE_TYPE = Map.of(
            "가로변", "길가", "공원", "공원", "화장실", "공중화장실", "주차장", "주차장", "건물", "건물 앞");

    static void describeName(StringBuilder sb, GuidePoint p) {
        String name = p.name() == null ? "" : p.name().strip();
        if (name.isEmpty() || p.kind() == com.safeschoolroad.facility.FacilityKind.CROSSWALK) {
            return;
        }
        switch (p.kind()) {
            case SCHOOL -> sb.append(" / 학교 이름: ").append(name);
            case CCTV, CHILD_SAFETY_HOUSE -> sb.append(" / 길 이름: ").append(name);
            case EMERGENCY_BELL -> {
                for (String part : name.split("\\s*·\\s*")) {
                    if (PLACE_TYPE.containsKey(part)) {
                        sb.append(" / 설치 장소: ").append(PLACE_TYPE.get(part));
                    } else if (part.endsWith("로") || part.endsWith("길")) {
                        sb.append(" / 길 이름: ").append(part);
                    }
                }
            }
            default -> sb.append(" / 이름: ").append(name);
        }
    }

    private static String reasonText(PointReason reason) {
        return switch (reason) {
            case CROSSING -> "이 횡단보도를 건넘";
            case TURN -> "이 근처에서 길을 꺾음";
            case GAP -> "길을 따라 지나감";
        };
    }

    // 모델이 ```json ... ``` 으로 감싸 보내기도 한다
    Map<Integer, String> parse(String reply) throws Exception {
        String json = reply == null ? "" : reply.strip();
        int start = json.indexOf('['), end = json.lastIndexOf(']');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("JSON 배열이 없음");
        }
        JsonNode arr = objectMapper.readTree(json.substring(start, end + 1));
        Map<Integer, String> texts = new LinkedHashMap<>();
        for (JsonNode n : arr) {
            texts.put(n.path("seq").asInt(), n.path("text").asText("").strip());
        }
        return texts;
    }
}
