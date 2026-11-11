package com.safeschoolroad.route;

import com.safeschoolroad.member.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface RouteRepository extends JpaRepository<Route, Long> {

    List<Route> findByOwnerOrderByIdAsc(Member owner);

    @Query("select count(r) from Route r where r.owner = :owner and r.changeStatus = com.safeschoolroad.route.Route.ChangeStatus.CHANGED")
    long countChanged(Member owner);

    List<Route> findByRegisteredByAndOwnerNot(Member registeredBy, Member owner);
}
