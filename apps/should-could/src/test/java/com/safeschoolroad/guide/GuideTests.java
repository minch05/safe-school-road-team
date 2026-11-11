package com.safeschoolroad.guide;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safeschoolroad.facility.FacilityKind;
import com.safeschoolroad.facility.SignalStatus;
import com.safeschoolroad.route.Route;
import com.safeschoolroad.route.analysis.PointReason;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 안내문 검증·다시 만들기 (FR-09, HANDOFF 5-8)
class GuideTests {

    private final List<GuidePoint> points = List.of(
            new GuidePoint(1, FacilityKind.CROSSWALK, "", SignalStatus.ESTIMATED, PointReason.CROSSING, false),
            new GuidePoint(2, FacilityKind.SCHOOL, "서울면일초등학교", null, PointReason.TURN, false),
            new GuidePoint(3, FacilityKind.EMERGENCY_BELL, "가로변 · 용마산로", null, PointReason.GAP, true));

    private static Map<Integer, String> texts(String a, String b, String c) {
        return Map.of(1, a, 2, b, 3, c);
    }

    @Test
    void 좋은_문장은_통과() {
        assertThat(GuideValidator.validate(points, texts(
                "횡단보도예요. 멈춰서 신호와 양쪽을 확인하고 건너요.",
                "서울면일초등학교 쪽에서 길을 꺾어요. 지도의 선을 보고 따라가요.",
                "용마산로를 따라 안전비상벨을 지나요. 거의 다 왔어요!"))).isEmpty();
    }

    @Test
    void 이름_없는_횡단보도에_다른_지점의_길이나_학교_이름을_붙이면_막는다() {
        for (String first : List.of(
                "용마산로 횡단보도예요. 멈춰서 신호와 양쪽을 확인하고 건너요.",
                "용마산로를 건너는 횡단보도예요. 멈춰서 신호와 양쪽을 확인해요.",
                "서울면일초등학교 앞 횡단보도예요. 멈춰서 신호와 양쪽을 확인해요.")) {
            assertThat(GuideValidator.validate(points, texts(first,
                    "서울면일초등학교 쪽에서 길을 꺾어요.", "용마산로를 따라 비상벨을 지나요.")))
                    .anyMatch(e -> e.startsWith("1번") && e.contains("이름 없는 횡단보도"));
        }
    }

    @Test
    void 이름_없는_횡단보도의_일반적인_길_표현은_허용한다() {
        for (String word : List.of("큰길", "골목길", "찻길", "도로")) {
            assertThat(GuideValidator.validate(points, texts(
                    word + "의 횡단보도예요. 멈춰서 신호와 양쪽을 확인하고 건너요.",
                    "서울면일초등학교 쪽에서 길을 꺾어요.", "용마산로를 따라 비상벨을 지나요.")))
                    .isEmpty();
        }
    }

    @Test
    void 공백_이름도_없는_이름으로_판정한다() {
        var unnamed = List.of(new GuidePoint(1, FacilityKind.CROSSWALK, "  ", SignalStatus.UNKNOWN,
                PointReason.CROSSING, false), points.get(2));
        assertThat(GuideValidator.validate(unnamed, Map.of(
                1, "용마산로 횡단보도예요. 멈춰서 신호와 양쪽을 확인해요.",
                3, "용마산로를 따라 비상벨을 지나요.")))
                .anyMatch(e -> e.contains("이름 없는 횡단보도"));
    }

    @Test
    void 전체_목록이_아니므로_첫번째나_마지막_시설이라고_단정하지_않는다() {
        for (String order : List.of("마지막", "첫 번째", "두번째", "3번째")) {
            assertThat(GuideValidator.validate(points, texts(
                    order + " 횡단보도 앞에서 멈춰서 신호와 양쪽을 확인하고 건너요.",
                    "서울면일초등학교 쪽에서 길을 꺾어요.", "비상벨을 지나요. 거의 다 왔어요.")))
                    .anyMatch(e -> e.contains("마지막 시설이라고 단정"));
        }
    }

    @Test
    void 횡단보도에서_신호만_보거나_주변만_살피는_문장을_막는다() {
        for(String first:List.of("횡단보도 앞에서 멈춰서 신호를 확인한 뒤 건너요.",
                "횡단보도예요. 멈춰서 신호와 주변을 살피고 건너요.")) {
            assertThat(GuideValidator.validate(points,texts(first,"서울면일초등학교 쪽에서 길을 꺾어요.","비상벨을 지나요.")))
                    .anyMatch(e->e.contains("양쪽 확인 안내"));
        }
        for(String sides:List.of("양쪽","양옆","좌우")) {
            assertThat(GuideValidator.validate(points,texts("횡단보도 앞에서 멈춰서 신호와 "+sides+"를 확인하고 건너요.",
                    "서울면일초등학교 쪽에서 길을 꺾어요.","비상벨을 지나요."))).isEmpty();
        }
    }

    @Test
    void 목록에_없는_장소를_잡는다() {
        assertThat(GuideValidator.validate(points, texts(
                "횡단보도예요. 멈춰서 신호와 양쪽을 확인하고 건너요.",
                "GS편의점 앞에서 길을 꺾어요.",
                "사가정로를 따라 걸어요.")))
                .anyMatch(e -> e.contains("편의점")).anyMatch(e -> e.contains("사가정로"));
    }

    @Test
    void 방향과_거리를_지어내면_잡는다() {
        assertThat(GuideValidator.validate(points, texts(
                "횡단보도예요. 멈춰서 확인하고 건너요.",
                "서울면일초등학교에서 왼쪽으로 꺾어요.",
                "100m 더 걸어요.")))
                .anyMatch(e -> e.startsWith("2번") && e.contains("방향")).anyMatch(e -> e.startsWith("3번"));
    }

    @Test
    void 신호가_확실하지_않은_횡단보도에서_초록불을_말하면_잡는다() {
        assertThat(GuideValidator.validate(points, texts(
                "초록불이 켜지면 건너요.",
                "서울면일초등학교 쪽에서 길을 꺾어요.",
                "안전비상벨을 지나요.")))
                .anyMatch(e -> e.contains("신호가 있다고 가정")).anyMatch(e -> e.contains("멈춰서 확인"));
    }

    @Test
    void 안전을_약속하거나_미리_도착했다고_하면_잡는다() {
        assertThat(GuideValidator.validate(points, texts(
                "횡단보도예요. 멈춰서 신호와 양쪽을 확인하고 건너요.",
                "서울면일초등학교 쪽이에요. CCTV가 우리를 지켜줘요. 거의 다 왔어요.",
                "안전비상벨을 지나면 도착했어요!")))
                .anyMatch(e -> e.startsWith("2번") && e.contains("안전을 약속"))
                .anyMatch(e -> e.startsWith("2번") && e.contains("마지막 지점이 아닌데"))
                .anyMatch(e -> e.startsWith("3번") && e.contains("도착했다고"));
    }

    @Test
    void 횡단보도에서_신호_확인을_빼거나_위치를_지어내면_잡는다() {
        assertThat(GuideValidator.validate(points, texts(
                "횡단보도 앞에서 멈춰서 양옆을 보고 건너요.",
                "서울면일초등학교 쪽에서 길을 꺾어요.",
                "머리 위에 비상벨이 있어요.")))
                .anyMatch(e -> e.startsWith("1번") && e.contains("신호"))
                .anyMatch(e -> e.startsWith("3번") && e.contains("위치 묘사"));
    }

    @Test
    void 지점_정보가_없거나_앞_문장과_같거나_어색하면_잡는다() {
        assertThat(GuideValidator.validate(points, texts(
                "횡단보도예요. 멈춰서 신호와 양쪽을 확인하고 건너요.",
                "길을 따라 바르게 걸어요.",
                "길을 따라 바르게 걸어요.")))
                .anyMatch(e -> e.startsWith("2번") && e.contains("시설"))
                .anyMatch(e -> e.startsWith("3번") && e.contains("똑같아요"));
        assertThat(GuideValidator.validate(points, texts(
                "횡단보도예요. 멈춰서 신호와 양쪽을 확인하고 건너요.",
                "서울면일초등학교에서 여기서 길을 꺾어요.",
                "용마산로 길가의 비상벨을 지나요. 거의 다 왔어요.")))
                .anyMatch(e -> e.startsWith("2번") && e.contains("어색한"));
    }

    @Test
    void 꺾는_곳에서_꺾는_말이_빠지거나_안_꺾는_곳에서_꺾으라고_하면_잡는다() {
        assertThat(GuideValidator.validate(points, texts(
                "횡단보도 앞에서 멈춰서 신호와 양쪽을 확인하고 건너요. 그리고 길을 꺾어요.",
                "서울면일초등학교가 보여요.",
                "용마산로 길가의 비상벨을 지나요. 거의 다 왔어요.")))
                .anyMatch(e -> e.startsWith("1번") && e.contains("꺾는 곳이 아니에요"))
                .anyMatch(e -> e.startsWith("2번") && e.contains("꺾는 곳이에요"));
    }

    @Test
    void 떨어져_있어도_같은_경로_안에서_같은_문장이면_잡는다() {
        List<GuidePoint> pts = List.of(
                new GuidePoint(1, FacilityKind.CROSSWALK, "", SignalStatus.UNKNOWN, PointReason.CROSSING, false),
                new GuidePoint(2, FacilityKind.CCTV, "면목로", null, PointReason.GAP, false),
                new GuidePoint(3, FacilityKind.CROSSWALK, "", SignalStatus.UNKNOWN, PointReason.CROSSING, false));
        assertThat(GuideValidator.validate(pts, Map.of(
                1, "횡단보도 앞에서 멈춰서 신호와 양쪽을 확인하고 건너요.",
                2, "면목로 CCTV를 지나 계속 걸어가요.",
                3, "횡단보도 앞에서 멈춰서 신호와 양쪽을 확인하고 건너요")))
                .anyMatch(e -> e.startsWith("3번") && e.contains("1번 문장과 똑같아요"));
    }

    @Test
    void 정해진_문장은_같은_종류가_이어져도_반복하지_않는다() {
        List<GuidePoint> pts = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            pts.add(new GuidePoint(i, FacilityKind.CROSSWALK, "", SignalStatus.ESTIMATED, PointReason.CROSSING, false));
        }
        pts.add(new GuidePoint(5, FacilityKind.EMERGENCY_BELL, "가로변 · 용마산로", null, PointReason.GAP, false));
        pts.add(new GuidePoint(6, FacilityKind.EMERGENCY_BELL, "가로변 · 용마산로", null, PointReason.GAP, true));
        Map<Integer, String> t = GuideService.template(pts);
        assertThat(new java.util.HashSet<>(t.values())).hasSize(6);
        assertThat(GuideValidator.validate(pts, t)).isEmpty();
    }

    @Test
    void 모든_종류와_이유의_정해진_문장이_검증을_통과한다() {
        int seq = 0;
        java.util.ArrayList<GuidePoint> all = new java.util.ArrayList<>();
        for (FacilityKind k : FacilityKind.values()) {
            if (k == FacilityKind.PEDESTRIAN_SIGNAL) {
                continue;
            }
            for (PointReason r : PointReason.values()) {
                if (k == FacilityKind.CROSSWALK && r == PointReason.TURN) {
                    continue; // 꺾는 곳 지점으로 횡단보도는 고르지 않는다 (RouteAnalyzer)
                }
                all.add(new GuidePoint(++seq, k, "서울면일초등학교", SignalStatus.UNKNOWN, r, false));
            }
        }
        Map<Integer, String> t = GuideService.template(all);
        // 같은 종류가 이어져 똑같은 문장이 되는 것은 정해진 문장에서는 괜찮다
        assertThat(GuideValidator.validate(all, t)).allMatch(e -> e.contains("똑같아요"));
    }

    @Test
    void 정해진_문장도_검증을_통과한다() {
        Map<Integer, String> t = GuideService.template(points);
        assertThat(GuideValidator.validate(points, t)).isEmpty();
        assertThat(t.get(3)).endsWith("거의 다 왔어요!");
    }

    @Test
    void 받침에_맞는_조사() {
        assertThat(TemplateGuide.withObjectMarker("면목로")).isEqualTo("면목로를");
        assertThat(TemplateGuide.withObjectMarker("사가정로50길")).isEqualTo("사가정로50길을");
    }

    // ── AI 흉내: 미리 정한 답을 차례로 돌려준다 ──
    private static GuideService serviceAnswering(String... replies) {
        Deque<String> queue = new ArrayDeque<>(List.of(replies));
        ChatModel fake = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(queue.pop()))));
            }
        };
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("chatModel", fake);
        ObjectProvider<ChatModel> provider = factory.getBeanProvider(ChatModel.class);
        return new GuideService(provider, new ObjectMapper());
    }

    private static final String GOOD = """
            ```json
            [{"seq":1,"text":"횡단보도예요. 멈춰서 신호와 양쪽을 확인하고 건너요."},
             {"seq":2,"text":"서울면일초등학교 쪽에서 길을 꺾어요."},
             {"seq":3,"text":"안전비상벨을 지나요. 거의 다 왔어요!"}]
            ```""";

    private static final String BAD = """
            [{"seq":1,"text":"초록불에 건너요."},{"seq":2,"text":"GS편의점에서 왼쪽으로 가요."},{"seq":3,"text":"다 왔어요."}]""";

    @Test
    void 검증에_걸리면_다시_만들어_통과한다() {
        GuideService.GuideResult r = serviceAnswering(BAD, GOOD).write(points, Route.Tone.LOWER);
        assertThat(r.source()).isEqualTo("AI");
        assertThat(r.attempts()).isEqualTo(2);
        assertThat(r.errors()).isNotEmpty();
        assertThat(r.texts().get(2)).contains("서울면일초등학교");
    }

    @Test
    void 옆_길_이름을_빌려온_안내는_다시_만들고_실패하면_정해진_문장을_쓴다() {
        String misleading = GOOD.replace("횡단보도예요.", "용마산로 횡단보도예요.");
        var corrected = serviceAnswering(misleading, GOOD).write(points, Route.Tone.LOWER);
        assertThat(corrected.source()).isEqualTo("AI");
        assertThat(corrected.attempts()).isEqualTo(2);
        assertThat(corrected.errors()).anyMatch(e -> e.contains("이름 없는 횡단보도"));
        var fallback = serviceAnswering(misleading, misleading, misleading).write(points, Route.Tone.LOWER);
        assertThat(fallback.source()).isEqualTo("TEMPLATE");
        assertThat(fallback.texts().get(1)).doesNotContain("용마산로");
        assertThat(GuideValidator.validate(points, fallback.texts())).isEmpty();
    }

    @Test
    void 세_번_모두_걸리면_정해진_문장을_쓴다() {
        GuideService.GuideResult r = serviceAnswering(BAD, "JSON 아님", BAD).write(points, Route.Tone.UPPER);
        assertThat(r.source()).isEqualTo("TEMPLATE");
        assertThat(r.attempts()).isEqualTo(3);
        assertThat(GuideValidator.validate(points, r.texts())).isEmpty();
    }

    @Test
    void AI가_없으면_바로_정해진_문장() {
        GuideService none = new GuideService(new DefaultListableBeanFactory().getBeanProvider(ChatModel.class), new ObjectMapper());
        GuideService.GuideResult r = none.write(points, Route.Tone.LOWER);
        assertThat(r.source()).isEqualTo("TEMPLATE");
        assertThat(r.attempts()).isZero();
    }
}
