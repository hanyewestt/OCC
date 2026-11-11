package com.csd.occ.disruption.domain.audit;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApplicationAuditEventRepository extends JpaRepository<ApplicationAuditEvent, UUID>, JpaSpecificationExecutor<ApplicationAuditEvent> {
    @Modifying
    @Query("delete from ApplicationAuditEvent event where event.occurredAt < :cutoff")
    int deleteBefore(@Param("cutoff") Instant cutoff);
}
