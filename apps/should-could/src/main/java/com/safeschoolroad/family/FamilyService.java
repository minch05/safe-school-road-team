package com.safeschoolroad.family;

import com.safeschoolroad.member.AccountService;
import com.safeschoolroad.member.Member;
import com.safeschoolroad.member.MemberRepository;
import com.safeschoolroad.route.Route;
import com.safeschoolroad.route.RouteRepository;
import com.safeschoolroad.trip.Trip;
import com.safeschoolroad.trip.TripRepository;
import com.safeschoolroad.trip.TripService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

// 보호자–아이 (FR-18, FR-23, 화면 P-01~P-04). HANDOFF 3장:
//  - 만 14세 미만은 본인 가입 불가 → 보호자가 아이 계정을 만들어 주고, 만들면서 자동 연동(보호자 동의 겸함)
//  - 14세 미만 아이 계정은 연동 해제 없음, 보호자가 삭제만 (보호자 없는 아이 계정이 생기지 않게)
//  - 만 14세 이상은 초대 코드로 연동(선택), 연동 해제 가능
@Service
public class FamilyService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final GuardianLinkRepository links;
    private final InviteCodeRepository invites;
    private final MemberRepository members;
    private final RouteRepository routes;
    private final TripRepository trips;
    private final AccountService accounts;
    private final PasswordEncoder passwordEncoder;
    private final com.safeschoolroad.push.PushPendingRepository pending;

    public FamilyService(GuardianLinkRepository links, InviteCodeRepository invites, MemberRepository members,
                         RouteRepository routes, TripRepository trips, AccountService accounts,
                         PasswordEncoder passwordEncoder,com.safeschoolroad.push.PushPendingRepository pending) {
        this.links = links;
        this.invites = invites;
        this.members = members;
        this.routes = routes;
        this.trips = trips;
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.pending = pending;
    }

    // ── 보호자가 될 수 있는지: 직접 가입한(14세 이상) 사람이고, 자기가 누군가의 자녀로 연동돼 있지 않아야 한다 ──
    private void requireGuardianCapable(Member m) {
        if (!m.isOver14() || links.findByChild(m).isPresent()) {
            throw new IllegalStateException("보호자와 연동된 계정은 아이 계정을 만들거나 초대 코드를 발급할 수 없어요.");
        }
    }

    // ── P-02 아이 계정 만들기 ──
    @Transactional
    public Member createChild(Member guardian, String username, String password, String nickname) {
        members.lockForFamilyChange(guardian.getId()).orElseThrow();
        requireGuardianCapable(guardian);
        if (username == null || !username.matches("^[a-z0-9]{4,20}$")) {
            throw new IllegalArgumentException("아이디는 영문 소문자와 숫자로 4~20자를 써 주세요.");
        }
        if (members.existsByUsername(username)) {
            throw new IllegalArgumentException("이미 쓰고 있는 아이디예요.");
        }
        if (password == null || password.length() < 8 || password.length() > 64) {
            throw new IllegalArgumentException("비밀번호는 8자 이상으로 써 주세요.");
        }
        String nick = nickname == null ? "" : nickname.strip();
        if (nick.isEmpty() || nick.length() > 10) {
            throw new IllegalArgumentException("별명은 1~10자로 써 주세요.");
        }
        Member child = members.save(new Member(username, passwordEncoder.encode(password), nick, false));
        links.save(new GuardianLink(guardian, child, GuardianLink.Type.CREATED));
        return child;
    }

    // ── P-01 보호자 홈: 아이별 오늘 상태 ──
    public record ChildSummary(long id, String nickname, GuardianLink.Type linkType, int routeCount, long changedCount,
                               Trip today, TripService.TodayState state) {
    }

    @Transactional(readOnly = true)
    public List<ChildSummary> childSummaries(Member guardian) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        return links.findByGuardian(guardian).stream().map(l -> {
            Member c = l.getChild();
            Trip today = trips.findFirstByChildAndStartedAtAfterOrderByStartedAtDesc(c, todayStart).orElse(null);
            return new ChildSummary(c.getId(), c.getNickname(), l.getType(), routes.findByOwnerOrderByIdAsc(c).size(),
                    routes.countChanged(c), today, TripService.stateOf(today, now));
        }).toList();
    }

    // ── P-03 자녀 상세 ──
    public record ChildDetail(Member child, GuardianLink.Type linkType, List<Route> routes, List<Trip> trips) {
    }

    @Transactional(readOnly = true)
    public ChildDetail child(Member guardian, long childId) {
        GuardianLink link = linkOf(guardian, childId);
        Member c = link.getChild();
        List<Route> list = routes.findByOwnerOrderByIdAsc(c);
        list.forEach(r -> r.getPoints().size());
        return new ChildDetail(c, link.getType(), list, trips.findTop10ByChildOrderByStartedAtDesc(c));
    }

    /** 14세 미만(보호자가 만든) 계정만 보호자가 비밀번호를 다시 정한다 */
    @Transactional
    public void resetChildPassword(Member guardian, long childId, String password) {
        GuardianLink link = linkOf(guardian, childId);
        if (link.getType() != GuardianLink.Type.CREATED) {
            throw new IllegalStateException("직접 가입한 자녀의 비밀번호는 자녀 본인이 바꿔요.");
        }
        accounts.setPassword(link.getChild(), password);
    }

    /** 14세 미만(보호자가 만든) 계정 삭제. 경로·안내문·출발/도착 기록이 모두 지워진다 */
    @Transactional
    public void deleteChild(Member guardian, long childId) {
        GuardianLink link = linkOf(guardian, childId);
        if (link.getType() != GuardianLink.Type.CREATED) {
            throw new IllegalStateException("직접 가입한 자녀는 삭제할 수 없고 연동만 해제할 수 있어요.");
        }
        accounts.deleteEverything(link.getChild());
    }

    /** 초대 코드로 연동한 14세 이상 자녀와 연동 해제 (보호자·자녀 어느 쪽에서든) */
    @Transactional
    public void unlink(Member me, long otherId) {
        GuardianLink link = links.findByChild(me).filter(l -> l.getGuardian().getId().equals(otherId))
                .orElseGet(() -> linkOf(me, otherId));
        if (link.getType() != GuardianLink.Type.INVITED) {
            throw new IllegalStateException("보호자가 만든 아이 계정은 연동을 해제할 수 없어요. 계정 삭제만 할 수 있어요.");
        }
        pending.deleteByGuardianLinkId(link.getId());
        links.delete(link);
    }

    private GuardianLink linkOf(Member guardian, long childId) {
        Member child = members.findById(childId).orElseThrow(() -> new NoSuchElementException("없는 회원"));
        return links.findByChild(child).filter(l -> l.getGuardian().getId().equals(guardian.getId()))
                .orElseThrow(() -> new AccessDeniedException("연동된 자녀가 아님"));
    }

    // ── P-04 초대 코드 (FR-23) ──
    public record LinkStatus(boolean linked,String guardian) {}

    @Transactional(readOnly = true)
    public LinkStatus linkStatus(Member child) {
        return links.findByChild(child).map(link -> new LinkStatus(true,link.getGuardian().getNickname()))
                .orElseGet(() -> new LinkStatus(false,""));
    }

    @Transactional
    public InviteCode issueInvite(Member guardian) {
        members.lockForFamilyChange(guardian.getId()).orElseThrow();
        requireGuardianCapable(guardian);
        invites.deleteAll(invites.findByGuardian(guardian)); // 이전 코드는 버린다
        String code;
        do {
            code = String.format("%06d", RANDOM.nextInt(1_000_000));
        } while (!invites.findUsable(code, LocalDateTime.now()).isEmpty());
        return invites.save(new InviteCode(guardian, code));
    }

    /** 코드를 넣으면 누구와 연동되는지 먼저 보여 준다 (보호자 별명) */
    @Transactional(readOnly = true)
    public Member previewInvite(Member child, String code) {
        return usableInvite(child, code).getGuardian();
    }

    @Transactional(readOnly = true)
    public InviteCode previewInviteCode(Member child,String code){return usableInvite(child,code);}

    @Transactional
    public void redeemInvite(Member child, String code) {redeemInvite(child,code,null);}

    @Transactional
    public void redeemInvite(Member child,String code,Long expectedInviteId) {
        members.lockForFamilyChange(child.getId()).orElseThrow();
        InviteCode invite = usableInvite(child, code,true);
        if(expectedInviteId!=null&&!expectedInviteId.equals(invite.getId()))throw new IllegalArgumentException("초대 코드가 바뀌었어요. 보호자를 다시 확인해 주세요.");
        invite.use();
        // 자녀로 연결된 계정이 이전에 발급한 보호자 초대는 더 이상 쓸 수 없다.
        invites.deleteAll(invites.findByGuardian(child));
        links.save(new GuardianLink(invite.getGuardian(), members.findById(child.getId()).orElseThrow(),
                GuardianLink.Type.INVITED));
    }

    private InviteCode usableInvite(Member child,String code){return usableInvite(child,code,false);}

    private InviteCode usableInvite(Member child, String code,boolean lock) {
        String digits = code == null ? "" : code.replaceAll("\\D", "");
        if (!child.isOver14()) {
            throw new IllegalStateException("초대 코드는 직접 가입한 만 14세 이상만 쓸 수 있어요.");
        }
        if (links.findByChild(child).isPresent()) {
            throw new IllegalStateException("이미 보호자와 연동돼 있어요.");
        }
        if (links.existsByGuardian(child)) {
            throw new IllegalStateException("자녀와 연동된 보호자 계정은 다른 보호자와 연동할 수 없어요.");
        }
        List<InviteCode> matches = lock ? invites.findUsableForUpdate(digits,LocalDateTime.now())
                : invites.findUsable(digits,LocalDateTime.now());
        if(matches.isEmpty())throw new IllegalArgumentException("코드가 맞지 않거나 시간이 지났어요. 보호자에게 새 코드를 받아 주세요.");
        if(matches.size()!=1)throw new IllegalArgumentException("같은 초대 코드가 겹쳤어요. 보호자에게 새 코드를 받아 주세요.");
        InviteCode invite = matches.get(0);
        if (invite.getGuardian().getId().equals(child.getId())) {
            throw new IllegalArgumentException("내가 발급한 코드는 쓸 수 없어요.");
        }
        if(!invite.getGuardian().isOver14()||links.findByChild(invite.getGuardian()).isPresent())
            throw new IllegalArgumentException("이 코드의 발급자는 현재 보호자로 연동할 수 없어요. 다른 보호자에게 코드를 받아 주세요.");
        return invite;
    }
}
