package com.csd.occ.ingestion.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ProvisioningOperationRepository extends JpaRepository<ProvisioningOperation,UUID> {
    @Modifying
    @Query(value="insert into ingestion.provisioning_operation(id,actor_subject,idempotency_key,email,display_name,role,status,created_at,updated_at,reconciliation_after,version) values(:id,:actor,:key,:email,:name,:role,'PENDING',:now,:now,:after,0) on conflict do nothing",nativeQuery=true)
    int reserve(@Param("id") UUID id,@Param("actor") String actor,@Param("key") String key,@Param("email") String email,@Param("name") String name,@Param("role") String role,@Param("now") Instant now,@Param("after") Instant after);
    Optional<ProvisioningOperation> findByActorSubjectAndIdempotencyKey(String actor,String key);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from ProvisioningOperation p where p.id=:id")
    Optional<ProvisioningOperation> lock(@Param("id") UUID id);
    @Query("select p.id from ProvisioningOperation p where p.status in (com.csd.occ.ingestion.domain.ProvisioningStatus.PENDING,com.csd.occ.ingestion.domain.ProvisioningStatus.PASSWORD_REQUIRED) and p.reconciliationAfter<=:now order by p.createdAt,p.id")
    List<UUID> recoverable(@Param("now") Instant now,Pageable page);
}
