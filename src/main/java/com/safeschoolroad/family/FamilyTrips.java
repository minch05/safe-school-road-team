package com.safeschoolroad.family;

import com.safeschoolroad.push.WebPush;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FamilyTrips {
    private final JdbcTemplate jdbc;
    private final WebPush push;

    public FamilyTrips(JdbcTemplate jdbc, WebPush push) {
        this.jdbc = jdbc;
        this.push = push;
    }

    public record Start(long routeId, UUID requestId) {}
    public record Summary(long id, String routeName, String startedAt, String arrivedAt) {}
    private record Trip(long id, long guardian, Long route) {}

    public List<Summary> recent(long guardian, long child) {
        return jdbc.query("""
                SELECT t.id, r.name, t.started_at, t.arrived_at FROM family_trip t
                JOIN family_link f ON f.child_id=t.child_id AND f.guardian_id=t.guardian_id
                LEFT JOIN saved_route r ON r.id=t.route_id WHERE t.guardian_id=? AND t.child_id=?
                ORDER BY t.id DESC LIMIT 10
                """, (rs, row) -> new Summary(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant().toString(),
                rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant().toString()), guardian, child);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public long start(long child, Start request) {
        if (request.requestId() == null) throw FamilyService.bad("출발 요청을 다시 보내 주세요.");
        if (jdbc.queryForList("SELECT id FROM member WHERE id=? FOR UPDATE", Long.class, child).isEmpty()) {
            throw FamilyService.missing();
        }
        String requestId = request.requestId().toString();
        long guardian = jdbc.queryForList("SELECT guardian_id FROM family_link WHERE child_id=?", Long.class, child)
                .stream().findFirst().orElseThrow(FamilyService::missing);
        var existing = jdbc.query("SELECT id,guardian_id,route_id FROM family_trip WHERE child_id=? AND request_id=?",
                (rs, row) -> new Trip(rs.getLong(1), rs.getLong(2), rs.getObject(3, Long.class)), child, requestId);
        if (!existing.isEmpty()) {
            Trip trip = existing.getFirst();
            if (trip.guardian() != guardian || !Long.valueOf(request.routeId()).equals(trip.route())) throw FamilyService.missing();
            return trip.id();
        }
        if (jdbc.queryForList("SELECT id FROM saved_route WHERE id=? AND member_id=? FOR UPDATE",
                Long.class, request.routeId(), child).isEmpty()) throw FamilyService.missing();

        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO family_trip(child_id,guardian_id,route_id,request_id) VALUES(?,?,?,?)",
                    new String[]{"ID"});
            statement.setLong(1, child);
            statement.setLong(2, guardian);
            statement.setLong(3, request.routeId());
            statement.setString(4, requestId);
            return statement;
        }, key);
        long id = key.getKey().longValue();
        push.enqueue(id, guardian, WebPush.Phase.START);
        return id;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void arrive(long child, long id) {
        if (jdbc.queryForList("SELECT id FROM member WHERE id=? FOR UPDATE", Long.class, child).isEmpty()) {
            throw FamilyService.missing();
        }
        var guardians = jdbc.queryForList("""
                SELECT t.guardian_id FROM family_trip t JOIN family_link f
                ON f.child_id=t.child_id AND f.guardian_id=t.guardian_id WHERE t.id=? AND t.child_id=?
                """, Long.class, id, child);
        if (guardians.isEmpty()) throw FamilyService.missing();
        if (jdbc.update("UPDATE family_trip SET arrived_at=CURRENT_TIMESTAMP WHERE id=? AND arrived_at IS NULL", id) == 1) {
            push.enqueue(id, guardians.getFirst(), WebPush.Phase.ARRIVE);
        }
    }
}
