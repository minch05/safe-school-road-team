package com.safeschoolroad.push;

import com.safeschoolroad.member.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    List<PushSubscription> findByMember(Member member);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<PushSubscription> findByEndpoint(String endpoint);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from PushSubscription s join fetch s.member where s.id=:id")
    Optional<PushSubscription> lockForDelivery(long id);

    boolean existsByMember(Member member);

    boolean existsByMemberAndEndpoint(Member member, String endpoint);

}
