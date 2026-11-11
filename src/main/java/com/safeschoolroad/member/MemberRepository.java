package com.safeschoolroad.member;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    // 같은 회원의 출발 생성 요청을 DB에서 순서대로 처리한다.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from Member m where m.id = :id")
    Optional<Member> lockForTripStart(long id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from Member m where m.id = :id")
    Optional<Member> lockForFamilyChange(long id);

    Optional<Member> findByUsername(String username);

    boolean existsByUsername(String username);
}
