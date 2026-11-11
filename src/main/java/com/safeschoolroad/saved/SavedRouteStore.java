package com.safeschoolroad.saved;

import com.safeschoolroad.evidence.RouteEvidence.KeyPoint;
import com.safeschoolroad.route.Point;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 저장 경로와 안내 지점 (FR-08). 경로와 지점은 한 트랜잭션으로 모두 저장되거나 모두 저장되지 않는다.
 * TMAP 경로선·거리·시간은 저장하지 않는다. 지점 좌표·이름은 공공자료 원본 값이다.
 */
@Repository
public class SavedRouteStore {
    private final JdbcTemplate jdbc;

    public SavedRouteStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Summary(long id, String name, String candidateId, int pointCount, String createdAt) { }
    public record StoredPoint(int seq, String kind, String name, String reason, double lat, double lng, int alongM,
                              String signalStatus, String source, String originalId, String guideText, String guideSource) { }
    public record Detail(long id, String name, Point start, Point end, String candidateId, int searchOption, String createdAt,
                         List<StoredPoint> points) { }

    public Optional<Long> memberId(String loginId) {
        return jdbc.queryForList("SELECT id FROM member WHERE login_id=?", Long.class, loginId).stream().findFirst();
    }

    @Transactional
    public long save(long memberId, String name, Point start, Point end, String candidateId, int searchOption,
                     List<KeyPoint> points, List<String> guideTexts) {
        return save(memberId, name, start, end, candidateId, searchOption, points, guideTexts, TemplateGuide.SOURCE);
    }

    @Transactional
    public long save(long memberId, String name, Point start, Point end, String candidateId, int searchOption,
                     List<KeyPoint> points, List<String> guideTexts, String guideSource) {
        if (points.isEmpty() || points.size() != guideTexts.size()
                || !List.of("AI", TemplateGuide.SOURCE).contains(guideSource)
                || guideTexts.stream().anyMatch(text -> text == null || text.isBlank() || text.length() > 300)) {
            throw new IllegalArgumentException("지점과 안내문 형식이 맞지 않습니다.");
        }
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO saved_route(member_id, name, start_lat, start_lng, end_lat, end_lng, candidate_id, search_option, point_count)
                    VALUES (?,?,?,?,?,?,?,?,?)""", new String[]{"ID"});
            ps.setLong(1, memberId); ps.setString(2, name);
            ps.setDouble(3, start.lat()); ps.setDouble(4, start.lng()); ps.setDouble(5, end.lat()); ps.setDouble(6, end.lng());
            ps.setString(7, candidateId); ps.setInt(8, searchOption); ps.setInt(9, points.size());
            return ps;
        }, key);
        Number generated = key.getKey();
        if (generated == null) throw new IllegalStateException("경로 번호를 받지 못했습니다.");
        long routeId = generated.longValue();
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            KeyPoint p = points.get(i);
            rows.add(new Object[]{routeId, i + 1, p.kind(), p.name() == null ? "" : p.name(), p.reason(), p.lat(), p.lng(), p.alongM(),
                    p.signalStatus(), p.source(), p.originalId(), guideTexts.get(i), guideSource});
        }
        jdbc.batchUpdate("""
                INSERT INTO saved_route_point(route_id, seq, kind, name, reason, lat, lng, along_m, signal_status, source, original_id, guide_text, guide_source)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""", rows);
        return routeId;
    }

    public List<Summary> list(long memberId) {
        return jdbc.query("SELECT id, name, candidate_id, point_count, created_at FROM saved_route WHERE member_id=? ORDER BY id DESC",
                (rs, i) -> new Summary(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getTimestamp(5).toString()), memberId);
    }

    /** 본인 경로만 돌려준다. 다른 사람 경로와 없는 경로는 구분하지 않는다. */
    public Optional<Detail> find(long memberId, long routeId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, start_lat, start_lng, end_lat, end_lng, candidate_id, search_option, created_at FROM saved_route WHERE id=? AND member_id=?",
                routeId, memberId);
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> r = new HashMap<>();
        rows.get(0).forEach((k, v) -> r.put(k.toLowerCase(), v));
        List<StoredPoint> points = jdbc.query("""
                SELECT seq, kind, name, reason, lat, lng, along_m, signal_status, source, original_id, guide_text, guide_source
                FROM saved_route_point WHERE route_id=? ORDER BY seq""",
                (rs, i) -> new StoredPoint(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getDouble(5), rs.getDouble(6),
                        rs.getInt(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12)), routeId);
        return Optional.of(new Detail(((Number) r.get("id")).longValue(), (String) r.get("name"),
                new Point(((Number) r.get("start_lat")).doubleValue(), ((Number) r.get("start_lng")).doubleValue()),
                new Point(((Number) r.get("end_lat")).doubleValue(), ((Number) r.get("end_lng")).doubleValue()),
                (String) r.get("candidate_id"), ((Number) r.get("search_option")).intValue(), String.valueOf(r.get("created_at")), points));
    }
}
