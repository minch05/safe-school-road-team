package com.safeschoolroad.member;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Optional;

// 로그인한 회원 꺼내기
@Component
public class CurrentMember {

    private final MemberRepository members;

    public CurrentMember(MemberRepository members) {
        this.members = members;
    }

    public Optional<Member> find(Authentication auth) {
        if (auth == null || auth instanceof AnonymousAuthenticationToken || !auth.isAuthenticated()) {
            return Optional.empty();
        }
        return members.findByUsername(auth.getName());
    }

    public Member get(Authentication auth) {
        return find(auth).orElseThrow(() -> new IllegalStateException("로그인이 필요합니다"));
    }
}
