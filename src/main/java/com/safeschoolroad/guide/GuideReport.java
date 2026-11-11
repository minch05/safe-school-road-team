package com.safeschoolroad.guide;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

// 안내문 품질 기록 보기 (FR-24 "검증 결과를 숫자로 기록"). 관리자 화면은 만들지 않으므로 명령으로 본다.
//
//   ./gradlew bootRun --args='--import-report-guides'
//
// (이름이 --import- 로 시작해야 켜 둔 앱과 포트가 겹치지 않는다)
@Component
public class GuideReport implements ApplicationRunner {

    public static final String OPTION = "import-report-guides";

    private final GuideLogRepository logs;
    private final ConfigurableApplicationContext context;

    public GuideReport(GuideLogRepository logs, ConfigurableApplicationContext context) {
        this.logs = logs;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return;
        }
        System.out.println(report(logs.findTop200ByOrderByIdDesc()));
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    static String report(List<GuideLog> list) {
        StringBuilder sb = new StringBuilder("\n===== 안내문 검증 기록 (최근 ").append(list.size()).append("건) =====\n");
        List<GuideLog> ai = list.stream().filter(l -> l.getAttempts() > 0).toList();
        long passedFirst = ai.stream().filter(l -> "AI".equals(l.getSource()) && l.getAttempts() == 1).count();
        long passed = ai.stream().filter(l -> "AI".equals(l.getSource())).count();
        sb.append("AI 호출 시도: ").append(ai.size()).append("건 / AI 없이 정해진 문장: ").append(list.size() - ai.size()).append("건\n");
        if (!ai.isEmpty()) {
            sb.append(String.format("첫 시도 통과: %d건 (%.0f%%)%n", passedFirst, 100.0 * passedFirst / ai.size()));
            sb.append(String.format("최종 통과(3번 안): %d건 (%.0f%%)%n", passed, 100.0 * passed / ai.size()));
            sb.append(String.format("평균 시도: %.2f번 (AI 검사 실패 또는 호출 실패 시 정해진 문장으로 전환)%n",
                    ai.stream().mapToInt(GuideLog::getAttempts).average().orElse(0)));
            sb.append("정해진 문장 전환: ").append(ai.size() - passed).append("건\n");
            // 걸린 문제 종류별
            Map<String, Long> kinds = new TreeMap<>(ai.stream()
                    .flatMap(l -> l.getErrors() == null || l.getErrors().isBlank() ? java.util.stream.Stream.empty()
                            : java.util.Arrays.stream(l.getErrors().split("\n")))
                    .map(GuideReport::kindOf)
                    .collect(Collectors.groupingBy(k -> k, Collectors.counting())));
            sb.append("걸린 문제: ").append(kinds.isEmpty() ? "없음" : kinds).append('\n');
            // 모델·말투별
            ai.stream().collect(Collectors.groupingBy(l -> l.getModel() + " / " + l.getTone()))
                    .entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append(String.format("  %s: %d건, 첫 시도 통과 %d건%n", e.getKey(), e.getValue().size(),
                            e.getValue().stream().filter(l -> "AI".equals(l.getSource()) && l.getAttempts() == 1).count())));
        }
        sb.append("이 기록은 생성 당시 자동 검사 결과예요. 현재 규칙으로 저장 문장을 다시 읽어 검사한 결과나 현장 정확도 검증은 아니에요.\n");
        return sb.toString();
    }

    private static String kindOf(String error) {
        if (error.contains("마지막 시설이라고 단정")) return "시설 순서 단정";
        if (error.contains("이름 없는 횡단보도")) return "횡단보도 이름 혼용";
        if (error.contains("꺾는 곳")) return "꺾는 안내";
        if (error.contains("마지막 지점이 아닌데") || error.contains("도착했다고")) return "도착 표현";
        if (error.contains("안전을 약속")) return "안전 약속";
        if (error.contains("문장에 이 지점의 시설")) return "지점 표시 빠짐";
        if (error.contains("똑같아요")) return "문장 반복";
        if (error.contains("어색한 표현")) return "어색한 표현";
        if (error.contains("위치 묘사")) return "위치 지어냄";
        if (error.contains("양쪽 확인 안내")) return "양쪽 확인 빠짐";
        if (error.contains("신호와 양쪽을 확인")) return "신호 확인 빠짐";
        if (error.contains("목록에 없는 장소")) return "없는 장소";
        if (error.contains("방향이나 거리")) return "방향·거리";
        if (error.contains("신호가 있다고 가정")) return "신호 가정";
        if (error.contains("멈춰서 확인")) return "멈춤 빠짐";
        if (error.contains("JSON")) return "형식 오류";
        if (error.contains("AI 호출 실패")) return "호출 실패";
        return "기타";
    }
}
