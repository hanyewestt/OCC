package com.csd.occ.ingestion.domain;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ApplicationAuditEventRepository extends JpaRepository<ApplicationAuditEvent,UUID> {
    boolean existsBySourceKey(String sourceKey);

    @Modifying
    @Query("delete from ApplicationAuditEvent event where event.occurredAt < :cutoff")
    int deleteBefore(@Param("cutoff") Instant cutoff);
}
