package com.csd.occ.diagnosis.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecommendationDeliveryRepository extends JpaRepository<RecommendationDelivery, UUID> {
    java.util.Optional<RecommendationDelivery> findByDiagnosisId(UUID diagnosisId);

    @Query(value = "select * from diagnosis.recommendation_delivery where "
            + "((status = 'PENDING' and next_attempt_at <= :now) or (status = 'PROCESSING' and lease_until <= :now)) "
            + "order by created_at limit :batchSize for update skip locked", nativeQuery = true)
    List<RecommendationDelivery> findClaimable(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
