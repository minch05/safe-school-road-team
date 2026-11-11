package com.safeschoolroad.place;

import com.safeschoolroad.member.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlaceRepository extends JpaRepository<Place, Long> {

    List<Place> findByOwnerOrderByIdAsc(Member owner);

    Optional<Place> findByOwnerAndName(Member owner, String name);

}
