package com.safeschoolroad.family;

import com.safeschoolroad.member.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface InviteCodeRepository extends JpaRepository<InviteCode, Long> {

    @Query("select c from InviteCode c join fetch c.guardian where c.code = :code and c.usedAt is null and c.expiresAt > :now")
    List<InviteCode> findUsable(String code, LocalDateTime now);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from InviteCode c join fetch c.guardian where c.code = :code and c.usedAt is null and c.expiresAt > :now")
    List<InviteCode> findUsableForUpdate(String code, LocalDateTime now);

    List<InviteCode> findByGuardian(Member guardian);
}
