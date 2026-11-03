package com.safeschoolroad.route;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/routes")
public class RouteApi {
    private final TmapGateway tmap;
    public RouteApi(TmapGateway tmap) { this.tmap = tmap; }
    public record Request(Point start, Point end) { }
    public record Candidate(String candidateId, List<Integer> options, String label, int distanceM,
            int durationSec, List<Point> line, String dataCoverage) { }

    @PostMapping("/candidates") ResponseEntity<?> candidates(@RequestBody Request request) {
        if (request == null || request.start() == null || request.end() == null
                || !request.start().inSupportedArea() || !request.end().inSupportedArea()
                || request.start().equals(request.end())) {
            return ResponseEntity.badRequest().body(Map.of("error", "서울 범위의 서로 다른 출발·도착 좌표를 선택해 주세요."));
        }
        List<Candidate> choices = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int option : List.of(0, 4, 10)) {
            try {
                var route = tmap.walking(request.start(), request.end(), option);
                // Merge only paths whose complete rounded geometry agrees; never merge by distance alone.
                String geometry = route.line().stream().map(p -> TmapGateway.num(p.lat()) + "," + TmapGateway.num(p.lng()))
                        .collect(java.util.stream.Collectors.joining("|"));
                Integer same = seen.get(geometry);
                if (same != null) {
                    Candidate prior = choices.get(same);
                    List<Integer> options = new ArrayList<>(prior.options()); options.add(option);
                    choices.set(same, new Candidate(prior.candidateId(), List.copyOf(options), prior.label(),
                            prior.distanceM(), prior.durationSec(), prior.line(), prior.dataCoverage()));
                } else {
                    seen.put(geometry, choices.size());
                    choices.add(new Candidate("candidate-" + option, List.of(option),
                            switch(option) { case 0 -> "기본"; case 4 -> "대로우선"; default -> "최단"; },
                            route.distanceM(), route.durationSec(), route.line(),
                            "시설 근거는 중랑구 10건 개발용 표본만 표시; 안전 점수로 해석하지 마세요."));
                }
            } catch (TmapGateway.ProviderUnavailable error) {
                warnings.add(option + "번 탐색: " + error.getMessage());
            }
        }
        if (choices.isEmpty()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore()).body(Map.of("error", "현재 실제 후보 경로를 가져올 수 없습니다.", "details", warnings));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("candidates", choices, "warnings", warnings,
                "source", "TMAP 보행자 경로, 한국 날짜 기준 당일 DB 캐시 사용", "safetyScore", "제공하지 않음"));
    }
}
