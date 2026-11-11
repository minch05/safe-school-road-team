package com.safeschoolroad.trip;

import com.safeschoolroad.member.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<Trip> findByStartRequestId(String startRequestId);

    List<Trip> findTop10ByChildOrderByStartedAtDesc(Member child);

    Optional<Trip> findFirstByChildAndStartedAtAfterOrderByStartedAtDesc(Member child, LocalDateTime after);

    // 도착 지연 알림 후보: 도착 안 함, 알림 안 보냄, 하루 안에 출발
    @Query("select t from Trip t join fetch t.child where t.arrivedAt is null and t.delayAlertSentAt is null and t.startedAt > :since")
    List<Trip> findOpen(LocalDateTime since);

    List<Trip> findByChild(Member child);

    // 동시에 들어온 도착 요청 중 최초 요청만 기록한다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Trip t set t.arrivedAt = :arrivedAt, t.arrivedAuto = :auto where t.id = :id and t.child.id = :childId and t.arrivedAt is null")
    int recordArrival(long id, long childId, LocalDateTime arrivedAt, boolean auto);

    // 후보 조회 후 도착했거나 다른 처리기가 알렸다면 지연 알림을 차지하지 않는다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Trip t set t.delayAlertSentAt = :now where t.id = :id and t.arrivedAt is null and t.delayAlertSentAt is null and t.startedAt > :since and t.expectedSeconds = :expectedSeconds and t.expectedSeconds > 0")
    int recordDelayAlert(long id, LocalDateTime now, LocalDateTime since, int expectedSeconds);

    // 예상 소요 시간은 TMAP 응답이라 24시간이 지나면 지운다 (약관, HANDOFF 5-6)
    @Modifying
    @Query("update Trip t set t.expectedSeconds = 0 where t.startedAt < :cutoff and t.expectedSeconds > 0")
    int clearExpectedBefore(LocalDateTime cutoff);
}
