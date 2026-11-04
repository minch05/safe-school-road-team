package com.safeschoolroad.route;

import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
        if (request == null || !RouteCandidates.validRequest(request.start(), request.end())) {
            return ResponseEntity.badRequest().body(Map.of("error", "서울 범위의 서로 다른 출발·도착 좌표를 선택해 주세요."));
        }
        var result = RouteCandidates.build(tmap, request.start(), request.end());
        List<Candidate> choices = result.built().stream().map(RouteCandidates.Built::candidate).toList();
        if (choices.isEmpty()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore()).body(Map.of("error", "현재 실제 후보 경로를 가져올 수 없습니다.", "details", result.warnings()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("candidates", choices, "warnings", result.warnings(),
                "source", "TMAP 보행자 경로, 한국 날짜 기준 당일 DB 캐시 사용", "safetyScore", "제공하지 않음"));
    }
}
