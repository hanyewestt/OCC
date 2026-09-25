package com.csd.occ.bunching.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertEventRepository extends JpaRepository<AlertEvent, UUID> {
    @Query(value = "select pg_notify('bunching_alert', :payload)", nativeQuery = true)
    Object notifyListeners(@Param("payload") String payload);
}
