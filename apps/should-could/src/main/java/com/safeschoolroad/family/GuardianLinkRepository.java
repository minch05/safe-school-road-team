package com.safeschoolroad.family;

import com.safeschoolroad.member.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface GuardianLinkRepository extends JpaRepository<GuardianLink, Long> {

    @Query("select l from GuardianLink l join fetch l.child where l.guardian = :guardian order by l.id")
    List<GuardianLink> findByGuardian(Member guardian);

    @Query("select l from GuardianLink l join fetch l.guardian where l.child = :child")
    Optional<GuardianLink> findByChild(Member child);

    boolean existsByGuardianAndChild(Member guardian, Member child);

    boolean existsByGuardian(Member guardian);
}
