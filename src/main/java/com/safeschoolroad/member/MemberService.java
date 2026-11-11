package com.safeschoolroad.member;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    public MemberService(MemberRepository memberRepository, PasswordEncoder passwordEncoder) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public boolean isUsernameTaken(String username) {
        return memberRepository.existsByUsername(username);
    }

    // 직접 가입(만 14세 이상). 아이디가 이미 있으면 DuplicateUsernameException.
    @Transactional
    public Member signup(SignupForm form) {
        if (memberRepository.existsByUsername(form.getUsername())) {
            throw new DuplicateUsernameException();
        }
        Member member = new Member(
                form.getUsername(),
                passwordEncoder.encode(form.getPassword()),
                form.getNickname(),
                true);
        try {
            return memberRepository.saveAndFlush(member);
        } catch (DataIntegrityViolationException e) {
            // 두 사람이 거의 동시에 같은 아이디로 가입한 경우
            throw new DuplicateUsernameException();
        }
    }

    @Transactional(readOnly = true)
    public Member getByUsername(String username) {
        return memberRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("회원을 찾을 수 없음: " + username));
    }

    public static class DuplicateUsernameException extends RuntimeException {
    }
}
