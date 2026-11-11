package com.safeschoolroad.member;

import com.safeschoolroad.family.GuardianLink;
import com.safeschoolroad.family.GuardianLinkRepository;
import com.safeschoolroad.family.InviteCodeRepository;
import com.safeschoolroad.place.PlaceRepository;
import com.safeschoolroad.push.PushSubscriptionRepository;
import com.safeschoolroad.route.RouteRepository;
import com.safeschoolroad.trip.TripRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// 계정 정보 바꾸기와 탈퇴·삭제. 지울 때는 그 사람의 경로·안내문·장소·출발/도착 기록·알림 구독·자동 로그인을 모두 지운다.
@Service
public class AccountService {

    private final MemberRepository members;
    private final RouteRepository routes;
    private final PlaceRepository places;
    private final TripRepository trips;
    private final PushSubscriptionRepository pushes;
    private final GuardianLinkRepository links;
    private final InviteCodeRepository invites;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public AccountService(MemberRepository members, RouteRepository routes, PlaceRepository places,
                          TripRepository trips, PushSubscriptionRepository pushes, GuardianLinkRepository links,
                          InviteCodeRepository invites, JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.members = members;
        this.routes = routes;
        this.places = places;
        this.trips = trips;
        this.pushes = pushes;
        this.links = links;
        this.invites = invites;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void changeNickname(Member member, String nickname) {
        members.findById(member.getId()).orElseThrow().changeNickname(nickname);
    }

    /** 현재 비밀번호가 맞아야 바꾼다. 보호자가 만든 아이 계정은 보호자가 바꾼다 */
    @Transactional
    public void changePassword(Member member, String current, String next) {
        Member m = members.findById(member.getId()).orElseThrow();
        if (!passwordEncoder.matches(current, m.getPassword())) {
            throw new IllegalArgumentException("지금 비밀번호가 맞지 않아요.");
        }
        setPassword(m, next);
    }

    @Transactional
    public void setPassword(Member member, String next) {
        if (next == null || next.length() < 8 || next.length() > 64) {
            throw new IllegalArgumentException("비밀번호는 8자 이상으로 써 주세요.");
        }
        Member m = members.findById(member.getId()).orElseThrow();
        m.changePassword(passwordEncoder.encode(next));
        // 다른 기기의 자동 로그인을 끊는다
        jdbc.update("DELETE FROM persistent_logins WHERE username = ?", m.getUsername());
    }

    /**
     * 본인 탈퇴. 보호자가 만든 아이 계정이 있으면 먼저 아이 계정을 지워야 한다(보호자 없는 아이 계정이 생기지 않게, HANDOFF 3장).
     * 보호자가 만든 아이 계정 자신은 탈퇴할 수 없다(보호자가 삭제).
     */
    @Transactional
    public void withdraw(Member member) {
        Member m = members.findById(member.getId()).orElseThrow();
        links.findByChild(m).filter(l -> l.getType() == GuardianLink.Type.CREATED).ifPresent(l -> {
            throw new IllegalStateException("보호자가 만든 계정은 보호자만 삭제할 수 있어요.");
        });
        List<GuardianLink> children = links.findByGuardian(m);
        if (children.stream().anyMatch(l -> l.getType() == GuardianLink.Type.CREATED)) {
            throw new IllegalStateException("보호자가 만든 아이 계정이 있어요. 아이 계정을 먼저 삭제해 주세요.");
        }
        deleteEverything(m);
    }

    /** 계정과 그 사람의 모든 기록을 지운다. 호출하는 쪽에서 권한을 확인한다 */
    @Transactional
    public void deleteEverything(Member m) {
        routes.deleteAll(routes.findByOwnerOrderByIdAsc(m));        // 핵심 지점·안내문도 함께
        // 이 사람이 대신 등록해 준 아이 경로는 아이 것이라 남기고, 등록자를 아이 본인으로 바꾼다
        routes.findByRegisteredByAndOwnerNot(m, m).forEach(r -> r.reassignRegistrantToOwner());
        // 한꺼번에 지우는 쿼리 대신 읽어서 지운다 (같은 트랜잭션에서 읽어 둔 것이 지운 회원을 가리키지 않게)
        places.deleteAll(places.findByOwnerOrderByIdAsc(m));
        trips.deleteAll(trips.findByChild(m));
        jdbc.update("DELETE FROM push_pending WHERE recipient_id = ? OR guardian_link_id IN (SELECT id FROM guardian_link WHERE child_id = ? OR guardian_id = ?)", m.getId(), m.getId(), m.getId());
        pushes.deleteAll(pushes.findByMember(m));
        invites.deleteAll(invites.findByGuardian(m));
        links.findByChild(m).ifPresent(links::delete);
        links.deleteAll(links.findByGuardian(m));                   // 초대 코드로 연동한 14세 이상 자녀와는 연동만 끊긴다
        jdbc.update("DELETE FROM persistent_logins WHERE username = ?", m.getUsername());
        members.delete(m);
    }
}
