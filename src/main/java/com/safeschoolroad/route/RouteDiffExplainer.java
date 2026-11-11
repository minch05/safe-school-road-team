package com.safeschoolroad.route;

import com.safeschoolroad.route.RouteCandidateService.Candidate;
import com.safeschoolroad.route.analysis.RouteAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 경로 차이 설명 (FR-07). 후보가 2개 이상일 때만 부른다.
// 차이는 코드가 계산하고(facts), AI는 그것을 보호자가 읽기 쉬운 한두 문장으로 바꾸기만 한다.
// AI 문장에 facts 에 없는 숫자가 나오면 버리고 정해진 문장을 쓴다.
@Component
public class RouteDiffExplainer {

    private static final Logger log = LoggerFactory.getLogger(RouteDiffExplainer.class);
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private final com.safeschoolroad.guide.LlmModels models;
    // 무료 AI 하루 한도를 안내문에 모두 쓰려고 기본은 끈다. 코드가 만든 사실 문장도 정확하다 (켜려면 app.llm.route-diff=true)
    @org.springframework.beans.factory.annotation.Value("${app.llm.route-diff:false}")
    private boolean useAi;

    public RouteDiffExplainer(com.safeschoolroad.guide.LlmModels models) {
        this.models = models;
    }

    /** {text, source(AI/TEMPLATE)} */
    public Map<String, String> explain(List<Candidate> list) {
        List<String> facts = facts(list);
        String template = String.join(" ", facts);
        if (!models.isAvailable() || !useAi) {
            return Map.of("text", template, "source", "TEMPLATE");
        }
        try {
            // 가벼운 모델부터: 안내문에 쓸 모델의 하루 한도를 아낀다
            String reply = models.call("""
                            보호자에게 아이 통학 후보 경로의 차이를 설명합니다. 주어진 사실만 써서 한두 문장(150자 안)으로 씁니다.
                            사실에 없는 숫자·장소·판단(더 안전하다 등)을 만들지 않습니다. "미확인"은 "확인하지 못한"으로 씁니다.
                            "~해요" 말투로, 문장만 답합니다.""",
                    "사실:\n- " + String.join("\n- ", facts), true).content();
            String text = reply == null ? "" : reply.strip();
            if (!text.isEmpty() && text.length() <= 220 && numbersSubset(text, template)) {
                return Map.of("text", text, "source", "AI");
            }
            log.info("경로 차이 설명 검증 실패 → 정해진 문장 사용: {}", text);
        } catch (Exception e) {
            log.warn("경로 차이 설명 AI 실패: {}", e.getMessage());
        }
        return Map.of("text", template, "source", "TEMPLATE");
    }

    // 첫 번째 후보(대개 추천)를 기준으로 나머지가 무엇이 다른지. 각 차이는 {이어 쓸 때, 문장 끝일 때} 두 모양
    public static List<String> facts(List<Candidate> list) {
        Candidate base = list.get(0);
        RouteAnalysis b = base.analysis();
        List<String> facts = new ArrayList<>();
        for (Candidate c : list.subList(1, list.size())) {
            RouteAnalysis a = c.analysis();
            List<String[]> parts = new ArrayList<>();
            int dist = a.route().distanceMeters() - b.route().distanceMeters();
            if (Math.abs(dist) >= 10) {
                parts.add(dist > 0 ? new String[]{dist + "m 길고", dist + "m 길어요"}
                        : new String[]{-dist + "m 짧고", -dist + "m 짧아요"});
            }
            addCount(parts, "건너는 횡단보도가", a.crossingCount() - b.crossingCount(), "곳");
            addCount(parts, "신호를 확인하지 못한 횡단보도가", a.crossingsUnknown() - b.crossingsUnknown(), "곳");
            addCount(parts, "지나는 사고다발지역이", a.accidentAreas().size() - b.accidentAreas().size(), "곳");
            int safety = a.safetyFacilities() - b.safetyFacilities();
            if (Math.abs(safety) >= 2) {
                addCount(parts, "길가의 CCTV·비상벨이", safety, "개");
            }
            String head = c.label() + " 경로는 " + base.label() + " 경로보다 ";
            if (parts.isEmpty()) {
                facts.add(head + "거리와 위험요소가 거의 같아요.");
                continue;
            }
            StringBuilder sb = new StringBuilder(head);
            for (int i = 0; i < parts.size(); i++) {
                sb.append(i == parts.size() - 1 ? parts.get(i)[1] + "." : parts.get(i)[0] + ", ");
            }
            facts.add(sb.toString());
        }
        return facts;
    }

    private static void addCount(List<String[]> parts, String subject, int diff, String unit) {
        if (diff == 0) {
            return;
        }
        String n = subject + " " + Math.abs(diff) + unit;
        parts.add(diff > 0 ? new String[]{n + " 더 많고", n + " 더 많아요"} : new String[]{n + " 더 적고", n + " 더 적어요"});
    }

    private static boolean numbersSubset(String text, String facts) {
        Set<String> allowed = new HashSet<>();
        Matcher f = NUMBER.matcher(facts);
        while (f.find()) {
            allowed.add(f.group());
        }
        Matcher t = NUMBER.matcher(text);
        while (t.find()) {
            if (!allowed.contains(t.group())) {
                return false;
            }
        }
        return true;
    }
}
