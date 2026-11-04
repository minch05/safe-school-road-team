package com.safeschoolroad.evidence;

import com.safeschoolroad.route.RouteApi;
import com.safeschoolroad.route.RouteCandidates;
import com.safeschoolroad.route.TmapGateway;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 후보 경로별 근거 (4-10-08). 요청은 후보 조회와 같은 {start, end}이고, 같은 candidateId로 결과를 돌려준다.
 * 경로선은 서버가 당일 캐시에서 다시 읽으므로 화면이 보낸 선을 믿지 않고 외부 호출도 늘지 않는다.
 */
@RestController
public class EvidenceApi {
    private final TmapGateway tmap;
    private final EvidenceAnalyzer analyzer;
    private final EvidenceData data;

    public EvidenceApi(TmapGateway tmap, EvidenceAnalyzer analyzer, EvidenceData data) {
        this.tmap = tmap;
        this.analyzer = analyzer;
        this.data = data;
    }

    @PostMapping("/api/routes/evidence") ResponseEntity<?> evidence(@RequestBody RouteApi.Request request) {
        if (request == null || !RouteCandidates.validRequest(request.start(), request.end())) {
            return ResponseEntity.badRequest().body(Map.of("error", "서울 범위의 서로 다른 출발·도착 좌표를 선택해 주세요."));
        }
        var built = RouteCandidates.build(tmap, request.start(), request.end());
        if (built.built().isEmpty()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .body(Map.of("error", "현재 실제 후보 경로를 가져올 수 없습니다.", "details", built.warnings()));
        List<RouteEvidence> evidence = built.built().stream()
                .map(b -> analyzer.analyze(b.candidate().candidateId(), b.route())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
                "evidence", evidence,
                "warnings", built.warnings(),
                "safetyScore", "제공하지 않음",
                "note", "공공자료로 확인한 범위의 사실만 보여요. 숫자가 많고 적음으로 더 안전한 길을 정하지 않아요."));
    }

    /** 구별·자료 종류별 적재 현황. '자료 부족' 안내와 결과 보고서에 쓴다. */
    @GetMapping("/api/data/coverage") Map<String, Object> coverage() {
        List<EvidenceData.Coverage> rows = data.coverage();
        return Map.of("loaded", !rows.isEmpty(), "coverage", rows,
                "note", rows.isEmpty() ? "지원 지역 자료가 아직 적재되지 않았어요. --import-supported-data로 적재해요."
                        : "보안등 자료는 12개 구 모두 없고, 신호는 추정·미확인만 있어요.");
    }
}
