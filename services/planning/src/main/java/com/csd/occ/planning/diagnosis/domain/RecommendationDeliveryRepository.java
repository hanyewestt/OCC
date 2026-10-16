package com.csd.occ.planning.diagnosis.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecommendationDeliveryRepository extends JpaRepository<RecommendationDelivery, UUID> {
    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from RecommendationDelivery row where (row.status = 'DELIVERED' and row.deliveredAt < :cutoff) or (row.status = 'FAILED_TERMINAL' and row.terminalAt < :cutoff)")
    int deleteCompletedBefore(@Param("cutoff") Instant cutoff);
    java.util.Optional<RecommendationDelivery> findByDiagnosisId(UUID diagnosisId);

    @Query(value = "select * from planning.recommendation_job where "
            + "((status = 'PENDING' and next_attempt_at <= :now) or (status = 'PROCESSING' and lease_until <= :now)) "
            + "order by created_at limit :batchSize for update skip locked", nativeQuery = true)
    List<RecommendationDelivery> findClaimable(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
