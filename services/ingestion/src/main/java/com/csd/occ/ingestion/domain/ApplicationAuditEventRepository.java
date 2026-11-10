package com.csd.occ.ingestion.domain;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ApplicationAuditEventRepository extends JpaRepository<ApplicationAuditEvent,UUID> { boolean existsBySourceKey(String sourceKey); }
