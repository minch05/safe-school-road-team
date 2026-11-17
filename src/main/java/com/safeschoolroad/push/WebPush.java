package com.safeschoolroad.push;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WebPush {
    public enum Phase {
        START("연동된 자녀의 출발이 기록됐어요."), ARRIVE("연동된 자녀의 도착이 기록됐어요.");
        final String message;
        Phase(String message) { this.message = message; }
    }

    record Subscription(long id, String endpoint, String key, String secret) {}
    private record Pending(long id, int attempts, Phase phase, Subscription subscription) {}
    private final JdbcTemplate jdbc;
    private final PushTransport transport;

    WebPush(JdbcTemplate jdbc, PushTransport transport) {
        this.jdbc = jdbc;
        this.transport = transport;
    }

    String publicKey() {
        return transport.publicKey();
    }

    @Transactional
    void subscribe(long member, String endpoint, String key, String secret) {
        if (!PushEndpoint.allowed(endpoint) || !validKey(key, 65) || !validKey(secret, 16)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "알림 주소나 구독 키가 올바르지 않아요.");
        }
        if (publicKey().isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "서버 알림 키가 준비되지 않았어요.");
        String hash = hash(endpoint);
        // A shared browser must stop receiving its previous account's queued messages.
        jdbc.update("""
                INSERT INTO web_push_subscription(member_id,endpoint_hash,endpoint,p256dh,auth_secret) VALUES(?,?,?,?,?)
                ON DUPLICATE KEY UPDATE member_id=?, endpoint=?, p256dh=?, auth_secret=?
                """, member, hash, endpoint, key, secret, member, endpoint, key, secret);
        jdbc.update("""
                DELETE FROM web_push_pending WHERE subscription_id IN
                (SELECT id FROM web_push_subscription WHERE endpoint_hash=?)
                AND trip_id IN (SELECT id FROM family_trip WHERE guardian_id<>?)
                """, hash, member);
    }

    boolean registered(long member, String endpoint) {
        return !jdbc.queryForList("SELECT id FROM web_push_subscription WHERE endpoint_hash=? AND member_id=?",
                Long.class, hash(endpoint), member).isEmpty();
    }

    void unsubscribe(long member, String endpoint) {
        jdbc.update("DELETE FROM web_push_subscription WHERE endpoint_hash=? AND member_id=?", hash(endpoint), member);
    }

    int test(long member, String endpoint) {
        if (publicKey().isEmpty()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "서버 알림 키가 준비되지 않았어요.");
        var targets = jdbc.query("SELECT id,endpoint,p256dh,auth_secret FROM web_push_subscription WHERE member_id=? AND endpoint_hash=?",
                (rs, row) -> new Subscription(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), member, hash(endpoint));
        return (int) targets.stream().filter(target ->
                send(target, "시험 알림이에요.", "test") == PushTransport.Result.ACCEPTED).count();
    }

    public void enqueue(long trip, long guardian, Phase phase) {
        if (publicKey().isEmpty()) return;
        jdbc.update("""
                INSERT INTO web_push_pending(trip_id,subscription_id,phase)
                SELECT ?,id,? FROM web_push_subscription WHERE member_id=?
                """, trip, phase.name(), guardian);
    }

    @Scheduled(initialDelayString = "${app.push.poll-ms:5000}", fixedDelayString = "${app.push.poll-ms:5000}")
    public void deliverPending() {
        if (publicKey().isEmpty()) return;
        var ids = jdbc.queryForList("SELECT id FROM web_push_pending WHERE due_at<=CURRENT_TIMESTAMP ORDER BY id LIMIT 20", Long.class);
        for (long id : ids) {
            if (jdbc.update("UPDATE web_push_pending SET due_at=? WHERE id=? AND due_at<=CURRENT_TIMESTAMP",
                    after(45), id) != 1) continue;
            var targets = jdbc.query("""
                    SELECT p.id,p.attempts,p.phase,s.id,s.endpoint,s.p256dh,s.auth_secret FROM web_push_pending p
                    JOIN web_push_subscription s ON s.id=p.subscription_id
                    JOIN family_trip t ON t.id=p.trip_id AND t.guardian_id=s.member_id
                    JOIN family_link l ON l.child_id=t.child_id AND l.guardian_id=t.guardian_id
                    WHERE p.id=?
                    """, (rs, row) -> new Pending(rs.getLong(1), rs.getInt(2), Phase.valueOf(rs.getString(3)),
                    new Subscription(rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7))), id);
            if (targets.isEmpty()) {
                jdbc.update("DELETE FROM web_push_pending WHERE id=?", id);
                continue;
            }
            Pending p = targets.getFirst();
            var result = send(p.subscription(), p.phase().message, "pending-" + p.id());
            if (result == PushTransport.Result.RETRY && p.attempts() < 4) {
                jdbc.update("UPDATE web_push_pending SET attempts=attempts+1,due_at=? WHERE id=?",
                        after(15L << p.attempts()), id);
            } else {
                jdbc.update("DELETE FROM web_push_pending WHERE id=?", id);
            }
        }
    }

    private PushTransport.Result send(Subscription target, String message, String tag) {
        var result = transport.send(target, message, tag);
        if (result == PushTransport.Result.EXPIRED) {
            jdbc.update("DELETE FROM web_push_subscription WHERE id=? AND p256dh=? AND auth_secret=?",
                    target.id(), target.key(), target.secret());
        }
        return result;
    }

    private static Timestamp after(long seconds) {
        return Timestamp.from(Instant.now().plusSeconds(seconds));
    }

    private static boolean validKey(String value, int size) {
        if (value == null || value.length() > 100) return false;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            return decoded.length == size && (size != 65 || decoded[0] == 4);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String hash(String endpoint) {
        if (endpoint == null || endpoint.length() > 2048) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "알림 주소가 올바르지 않아요.");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(endpoint.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
