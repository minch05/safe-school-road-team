package com.safeschoolroad.family;

import com.safeschoolroad.member.AccountService;
import com.safeschoolroad.member.Member;
import com.safeschoolroad.member.MemberRepository;
import com.safeschoolroad.route.Route;
import com.safeschoolroad.route.RouteRepository;
import com.safeschoolroad.trip.Trip;
import com.safeschoolroad.trip.TripRepository;
import com.safeschoolroad.trip.TripService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 보호자–아이 규칙 (HANDOFF 3장). 내 컴퓨터 MySQL을 쓰지만 @Transactional 이라 끝나면 되돌린다.
@SpringBootTest
@Transactional
class FamilyServiceTests {

    @Autowired FamilyService family;
    @Autowired FamilyAccess access;
    @Autowired AccountService accounts;
    @Autowired MemberRepository members;
    @Autowired RouteRepository routes;
    @Autowired TripRepository trips;
    @Autowired InviteCodeRepository invites;

    private Member adult(String username) {
        return members.save(new Member(username, "{noop}password1", "어른", true));
    }

    @Test
    void 자녀로_연동되면_예전에_발급한_보호자_초대는_폐기된다() {
        Member parent=adult("testparent01"),teen=adult("testteen01"),other=adult("testteen02");
        InviteCode old=family.issueInvite(teen),incoming=family.issueInvite(parent);
        family.redeemInvite(teen,incoming.getCode());invites.flush();
        assertThat(invites.findByGuardian(teen)).isEmpty();
        assertThatThrownBy(()->family.previewInvite(other,old.getCode())).isInstanceOf(IllegalArgumentException.class);
        assertThat(access.guardianOf(other)).isEmpty();
        // 이전 버전에서 남은 행이 있어도 역할을 다시 확인한다.
        invites.saveAndFlush(new InviteCode(teen,old.getCode()));
        assertThatThrownBy(()->family.redeemInvite(other,old.getCode())).hasMessageContaining("현재 보호자로 연동할 수 없어요");
        assertThat(access.guardianOf(other)).isEmpty();
    }

    @Test
    void 아이_계정을_만들면_바로_연동된다() {
        Member parent = adult("testparent01");
        Member child = family.createChild(parent, "testchild01", "password1", "하늘");

        assertThat(child.isOver14()).isFalse();
        assertThat(access.guardianOf(child)).get().extracting(GuardianLink::getType).isEqualTo(GuardianLink.Type.CREATED);
        assertThat(access.isGuardian(parent)).isTrue();
        assertThat(access.canManage(parent, child)).isTrue();
        assertThat(access.canManage(child, parent)).isFalse();
    }

    @Test
    void 아이_계정은_다른_아이를_만들_수_없다() {
        Member parent = adult("testparent01");
        Member child = family.createChild(parent, "testchild01", "password1", "하늘");
        assertThatThrownBy(() -> family.createChild(child, "testchild02", "password1", "바다"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 보호자가_만든_아이는_연동_해제가_안_되고_삭제만() {
        Member parent = adult("testparent01");
        Member child = family.createChild(parent, "testchild01", "password1", "하늘");
        routes.save(new Route(child, parent, "학교 가기", "집", 37.59, 127.08, "학교", 37.6, 127.09, 0, Route.Tone.LOWER));
        trips.save(new Trip(child, null, "학교 가기", 600));

        assertThatThrownBy(() -> family.unlink(parent, child.getId())).isInstanceOf(IllegalStateException.class);
        family.deleteChild(parent, child.getId());

        long childId = child.getId();
        assertThat(members.findByUsername("testchild01")).isEmpty();
        assertThat(routes.findAll()).noneMatch(r -> r.getOwner().getId().equals(childId));
        assertThat(trips.findAll()).noneMatch(t -> t.getChild().getId().equals(childId));
    }

    @Test
    void 아이_계정이_있으면_보호자는_탈퇴할_수_없다() {
        Member parent = adult("testparent01");
        family.createChild(parent, "testchild01", "password1", "하늘");
        assertThatThrownBy(() -> accounts.withdraw(parent)).hasMessageContaining("아이 계정을 먼저");
    }

    @Test
    void 초대_코드로_14세_이상_자녀와_연동하고_해제할_수_있다() {
        Member parent = adult("testparent01");
        Member teen = adult("testteen01");
        InviteCode code = family.issueInvite(parent);

        assertThat(family.previewInvite(teen, code.getCode()).getId()).isEqualTo(parent.getId());
        family.redeemInvite(teen, code.getCode().substring(0, 3) + " " + code.getCode().substring(3));
        assertThat(access.guardianOf(teen)).get().extracting(GuardianLink::getType).isEqualTo(GuardianLink.Type.INVITED);

        // 같은 코드는 다시 쓸 수 없다
        Member other = adult("testteen02");
        assertThatThrownBy(() -> family.redeemInvite(other, code.getCode())).isInstanceOf(IllegalArgumentException.class);

        family.unlink(teen, parent.getId()); // 자녀 쪽에서 해제
        assertThat(access.guardianOf(teen)).isEmpty();
    }

    @Test
    void 아이_계정은_초대_코드를_쓸_수_없다() {
        Member parent = adult("testparent01");
        Member child = family.createChild(parent, "testchild01", "password1", "하늘");
        InviteCode code = family.issueInvite(adult("testparent02"));
        assertThatThrownBy(() -> family.redeemInvite(child, code.getCode())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 다른_보호자는_아이를_볼_수_없다() {
        Member parent = adult("testparent01");
        Member child = family.createChild(parent, "testchild01", "password1", "하늘");
        Member stranger = adult("teststranger");
        assertThatThrownBy(() -> family.child(stranger, child.getId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> access.resolveOwner(stranger, child.getId())).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void 오늘_상태() {
        LocalDateTime now = LocalDateTime.now();
        assertThat(TripService.stateOf(null, now)).isEqualTo(TripService.TodayState.NOT_STARTED);
        Trip walking = new Trip(null, null, "학교", 600);
        assertThat(TripService.stateOf(walking, now)).isEqualTo(TripService.TodayState.WALKING);
        // 예상 10분 + 15분이 지나면 "도착 표시 없음"
        assertThat(TripService.stateOf(walking, now.plusMinutes(26))).isEqualTo(TripService.TodayState.LATE);
        walking.arrive(false);
        assertThat(TripService.stateOf(walking, now.plusMinutes(26))).isEqualTo(TripService.TodayState.ARRIVED);
    }
}
