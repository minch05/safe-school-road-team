package com.safeschoolroad.explore;

import com.safeschoolroad.evidence.EvidenceAnalyzer;
import com.safeschoolroad.route.RouteApi;
import com.safeschoolroad.route.RouteCandidates;
import com.safeschoolroad.route.TmapGateway;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GuestRoutesApi {
    private static final int DAILY_LIMIT = 3;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final TmapGateway tmap;
    private final EvidenceAnalyzer analyzer;

    public GuestRoutesApi(JdbcTemplate jdbc, TransactionTemplate transaction, TmapGateway tmap, EvidenceAnalyzer analyzer) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.tmap = tmap;
        this.analyzer = analyzer;
    }

    @PostMapping("/api/guest/candidates")
    ResponseEntity<?> candidates(@RequestBody RouteApi.Request request,
            @CookieValue(name="team_guest", required=false) String cookie,
            HttpServletRequest http, HttpServletResponse response) {
        if (request == null || !RouteCandidates.validRequest(request.start(), request.end())) {
            return ResponseEntity.badRequest().body(Map.of("error", "서울 범위의 서로 다른 출발·도착 좌표를 선택해 주세요."));
        }
        String device = device(cookie, http, response);
        LocalDate day = LocalDate.now(SEOUL);
        Integer left = transaction.execute(status -> {
            jdbc.update("""
                    INSERT INTO team_guest_quota(quota_day,device_id,calls) VALUES(?,?,0)
                    ON DUPLICATE KEY UPDATE calls=calls""", day, device);
            if (jdbc.update("UPDATE team_guest_quota SET calls=calls+1 WHERE quota_day=? AND device_id=? AND calls<?",
                    day, device, DAILY_LIMIT) != 1) return -1;
            return DAILY_LIMIT - jdbc.queryForObject(
                    "SELECT calls FROM team_guest_quota WHERE quota_day=? AND device_id=?", Integer.class, day, device);
        });
        if (left == null || left < 0) return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .cacheControl(CacheControl.noStore()).body(Map.of("error", "오늘 미리보기 3회를 모두 사용했어요. 로그인하면 계속 볼 수 있어요."));
        var built = RouteCandidates.build(tmap, request.start(), request.end());
        if (built.built().isEmpty()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore()).body(Map.of("error", "현재 실제 후보 경로를 가져올 수 없습니다."));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
                "candidates", built.built().stream().map(RouteCandidates.Built::candidate).toList(),
                "evidence", built.built().stream().map(b -> analyzer.analyze(b.candidate().candidateId(), b.route())).toList(),
                "warnings", built.warnings(), "left", left));
    }

    private static String device(String cookie, HttpServletRequest request, HttpServletResponse response) {
        if (cookie != null && cookie.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) return cookie;
        String id = UUID.randomUUID().toString();
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("team_guest", id).httpOnly(true)
                .secure(request.isSecure()).sameSite("Lax").path("/").maxAge(30L * 24 * 3600).build().toString());
        return id;
    }
}
