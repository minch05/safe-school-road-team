package com.safeschoolroad.push;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.*;
public interface PushPendingRepository extends JpaRepository<PushPending,Long> {
    void deleteByGuardianLinkId(Long guardianLinkId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PushPending p where p.id=:id")
    Optional<PushPending> lockPending(long id);
    @Query("select p.id from PushPending p where (p.nextAttemptAt<=:now or p.expiresAt<=:now) order by p.id")
    List<Long> dueIds(LocalDateTime now,org.springframework.data.domain.Pageable page);
}
