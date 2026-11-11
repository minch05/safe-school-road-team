package com.safeschoolroad.guide;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GuideLogRepository extends JpaRepository<GuideLog, Long> {

    List<GuideLog> findTop200ByOrderByIdDesc();
}
