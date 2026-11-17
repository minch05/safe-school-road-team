package com.safeschoolroad.family;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FamilyService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;

    public FamilyService(JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    public record Person(long id, String nickname, String type) {}
    public record Family(long memberId, List<Person> children, Person guardian, boolean canBeGuardian) {}
    public record Invite(String code, int minutes) {}
    private record Account(String nickname, boolean adultSignup) {}

    public Family family(long member) {
        var children = jdbc.query("""
                SELECT m.id, m.nickname, l.link_type FROM family_link l
                JOIN member m ON m.id=l.child_id WHERE l.guardian_id=? ORDER BY m.id
                """, (rs, row) -> new Person(rs.getLong(1), rs.getString(2), rs.getString(3)), member);
        var guardian = jdbc.query("""
                SELECT m.id, m.nickname, l.link_type FROM family_link l
                JOIN member m ON m.id=l.guardian_id WHERE l.child_id=?
                """, (rs, row) -> new Person(rs.getLong(1), rs.getString(2), rs.getString(3)), member)
                .stream().findFirst().orElse(null);
        return new Family(member, children, guardian, account(member).adultSignup() && guardian == null);
    }

    public Optional<Long> memberId(String login) {
        return jdbc.queryForList("SELECT id FROM member WHERE login_id=?", Long.class, login).stream().findFirst();
    }

    public boolean linked(long guardian, long child) {
        return !jdbc.queryForList("SELECT child_id FROM family_link WHERE guardian_id=? AND child_id=?",
                Long.class, guardian, child).isEmpty();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void createChild(long guardian, String login, String password, String nickname) {
        lock(guardian);
        requireGuardian(guardian);
        String nick = nickname == null ? "" : nickname.strip();
        if (login == null || !login.matches("[a-z0-9_-]{4,30}") || nick.isBlank() || nick.length() > 60) {
            throw bad("아이디와 별명을 확인해 주세요.");
        }
        try {
            jdbc.update("INSERT INTO member(login_id,password_hash,nickname,age_14_or_older) VALUES(?,?,?,FALSE)",
                    login, encoded(password), nick);
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다.");
        }
        jdbc.update("INSERT INTO family_link(child_id,guardian_id,link_type) VALUES(?,?,'CREATED')",
                memberId(login).orElseThrow(), guardian);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void reset(long guardian, long child, String password) {
        requireCreated(guardian, child);
        jdbc.update("UPDATE member SET password_hash=? WHERE id=?", encoded(password), child);
        revokeRememberMe(child);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(long guardian, long child, String nickname) {
        requireCreated(guardian, child);
        if (!account(child).nickname().equals(nickname == null ? "" : nickname.strip())) {
            throw bad("삭제하려면 자녀의 별명을 정확히 입력해 주세요.");
        }
        revokeRememberMe(child);
        jdbc.update("DELETE FROM saved_route WHERE member_id=?", child);
        jdbc.update("DELETE FROM member WHERE id=?", child);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void unlink(long member, long other) {
        lockPair(member, other);
        var child = jdbc.queryForList("""
                SELECT child_id FROM family_link WHERE link_type='INVITED'
                AND ((guardian_id=? AND child_id=?) OR (guardian_id=? AND child_id=?))
                """, Long.class, member, other, other, member).stream().findFirst().orElseThrow(FamilyService::missing);
        jdbc.update("DELETE FROM web_push_pending WHERE trip_id IN (SELECT id FROM family_trip WHERE child_id=?)", child);
        jdbc.update("DELETE FROM family_link WHERE child_id=?", child);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Invite issue(long guardian) {
        lock(guardian);
        requireGuardian(guardian);
        byte[] random = new byte[12];
        RANDOM.nextBytes(random);
        String token = HexFormat.of().withUpperCase().formatHex(random);
        jdbc.update("DELETE FROM family_invite WHERE guardian_id=?", guardian);
        jdbc.update("INSERT INTO family_invite(guardian_id,token,expires_at) VALUES(?,?,?)",
                guardian, token, Timestamp.from(Instant.now().plusSeconds(600)));
        return new Invite(token, 10);
    }

    public Person preview(long child, String token) {
        long guardian = issuer(token);
        requireRedeemable(child, guardian);
        return new Person(guardian, account(guardian).nickname(), "INVITED");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void redeem(long child, long guardian, String token) {
        lockPair(child, guardian);
        if (issuer(token) != guardian) throw bad("보호자를 다시 확인해 주세요.");
        requireRedeemable(child, guardian);
        if (jdbc.update("DELETE FROM family_invite WHERE guardian_id=? AND token=? AND expires_at>CURRENT_TIMESTAMP",
                guardian, token) != 1) throw bad("코드가 만료됐어요. 새 코드를 받아 주세요.");
        jdbc.update("DELETE FROM family_invite WHERE guardian_id=?", child);
        jdbc.update("INSERT INTO family_link(child_id,guardian_id,link_type) VALUES(?,?,'INVITED')", child, guardian);
    }

    private long issuer(String token) {
        if (token == null || !token.matches("[A-F0-9]{24}")) throw bad("초대 코드를 확인해 주세요.");
        return jdbc.queryForList("SELECT guardian_id FROM family_invite WHERE token=? AND expires_at>CURRENT_TIMESTAMP",
                Long.class, token).stream().findFirst().orElseThrow(() -> bad("코드가 맞지 않거나 만료됐어요."));
    }

    private void requireRedeemable(long child, long guardian) {
        if (child == guardian || !account(child).adultSignup() || hasGuardian(child)
                || !jdbc.queryForList("SELECT child_id FROM family_link WHERE guardian_id=?", Long.class, child).isEmpty()) {
            throw bad("이 계정은 초대 코드로 연동할 수 없어요.");
        }
        requireGuardian(guardian);
    }

    private void requireCreated(long guardian, long child) {
        lockPair(guardian, child);
        if (jdbc.queryForList("SELECT child_id FROM family_link WHERE guardian_id=? AND child_id=? AND link_type='CREATED'",
                Long.class, guardian, child).isEmpty()) throw missing();
    }

    private void requireGuardian(long member) {
        if (!account(member).adultSignup() || hasGuardian(member)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "보호자 계정에서만 할 수 있어요.");
        }
    }

    private boolean hasGuardian(long member) {
        return !jdbc.queryForList("SELECT guardian_id FROM family_link WHERE child_id=?", Long.class, member).isEmpty();
    }

    private Account account(long member) {
        return jdbc.query("SELECT nickname,age_14_or_older FROM member WHERE id=?",
                (rs, row) -> new Account(rs.getString(1), rs.getBoolean(2)), member)
                .stream().findFirst().orElseThrow(FamilyService::missing);
    }

    private void lock(long member) {
        if (jdbc.queryForList("SELECT id FROM member WHERE id=? FOR UPDATE", Long.class, member).isEmpty()) throw missing();
    }

    private void lockPair(long first, long second) {
        lock(Math.min(first, second));
        if (first != second) lock(Math.max(first, second));
    }

    private void revokeRememberMe(long child) {
        jdbc.update("DELETE FROM persistent_logins WHERE username IN (SELECT login_id FROM member WHERE id=?)", child);
    }

    private String encoded(String password) {
        if (password == null || password.length() < 10 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw bad("비밀번호는 10자 이상, UTF-8 기준 72바이트 이하여야 해요.");
        }
        return passwords.encode(password);
    }

    static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "가족 계정 또는 경로를 찾을 수 없어요.");
    }
}
