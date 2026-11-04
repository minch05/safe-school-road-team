package com.safeschoolroad.route;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 후보 경로 만들기. 옵션 0·4·10을 요청하고 전체 경로선이 같을 때만 하나로 합친다.
 * 후보 조회·근거 분석·저장이 같은 규칙과 같은 candidateId를 쓰도록 한곳에 둔다.
 * TMAP 응답은 당일 캐시에서 다시 읽으므로 같은 날 다시 불러도 외부 호출이 늘지 않는다.
 */
public final class RouteCandidates {
    private RouteCandidates() { }

    public record Built(RouteApi.Candidate candidate, TmapGateway.RouteOption route) { }
    public record Result(List<Built> built, List<String> warnings) { }

    public static boolean validRequest(Point start, Point end) {
        return start != null && end != null && start.inSupportedArea() && end.inSupportedArea() && !start.equals(end);
    }

    public static Result build(TmapGateway tmap, Point start, Point end) {
        List<Built> built = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int option : List.of(0, 4, 10)) {
            try {
                var route = tmap.walking(start, end, option);
                // Merge only paths whose complete rounded geometry agrees; never merge by distance alone.
                String geometry = route.line().stream().map(p -> TmapGateway.num(p.lat()) + "," + TmapGateway.num(p.lng()))
                        .collect(Collectors.joining("|"));
                Integer same = seen.get(geometry);
                if (same != null) {
                    Built prior = built.get(same);
                    RouteApi.Candidate c = prior.candidate();
                    List<Integer> options = new ArrayList<>(c.options()); options.add(option);
                    built.set(same, new Built(new RouteApi.Candidate(c.candidateId(), List.copyOf(options), c.label(),
                            c.distanceM(), c.durationSec(), c.line(), c.dataCoverage()), prior.route()));
                } else {
                    seen.put(geometry, built.size());
                    built.add(new Built(new RouteApi.Candidate("candidate-" + option, List.of(option),
                            switch (option) { case 0 -> "기본"; case 4 -> "대로우선"; default -> "최단"; },
                            route.distanceM(), route.durationSec(), route.line(),
                            "경로 근거는 공공자료로 확인한 범위에서만 보여요. 안전 점수가 아니에요."), route));
                }
            } catch (TmapGateway.ProviderUnavailable error) {
                warnings.add(option + "번 탐색: " + error.getMessage());
            }
        }
        return new Result(built, warnings);
    }
}
