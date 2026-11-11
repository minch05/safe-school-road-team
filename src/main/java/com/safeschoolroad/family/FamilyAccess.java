package com.safeschoolroad.family;

import com.safeschoolroad.member.Member;
import com.safeschoolroad.member.MemberRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

// 누가 누구의 경로·장소를 볼 수 있는지. 본인, 또는 연동된 보호자만.
@Component
public class FamilyAccess {

    private final GuardianLinkRepository links;
    private final MemberRepository members;

    public FamilyAccess(GuardianLinkRepository links, MemberRepository members) {
        this.links = links;
        this.members = members;
    }

    @Transactional(readOnly = true)
    public boolean canManage(Member viewer, Member owner) {
        return viewer.getId().equals(owner.getId()) || links.existsByGuardianAndChild(viewer, owner);
    }

    /** ownerId 가 없으면 본인. 있으면 연동된 자녀여야 한다 */
    @Transactional(readOnly = true)
    public Member resolveOwner(Member viewer, Long ownerId) {
        if (ownerId == null || ownerId.equals(viewer.getId())) {
            return viewer;
        }
        Member owner = members.findById(ownerId).orElseThrow(() -> new AccessDeniedException("없는 회원"));
        if (!links.existsByGuardianAndChild(viewer, owner)) {
            throw new AccessDeniedException("연동된 자녀가 아님");
        }
        return owner;
    }

    /** 보호자와 연동된 자녀인지 (아이 화면·출발/도착 알림 대상) */
    @Transactional(readOnly = true)
    public Optional<GuardianLink> guardianOf(Member child) {
        return links.findByChild(child);
    }

    @Transactional(readOnly = true)
    public boolean isGuardian(Member member) {
        return links.existsByGuardian(member);
    }
}
