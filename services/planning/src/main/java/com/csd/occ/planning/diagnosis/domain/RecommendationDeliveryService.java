package com.csd.occ.planning.diagnosis.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecommendationDeliveryService {
    private final RecommendationDeliveryRepository deliveries;
    private final Clock clock;

    public RecommendationDeliveryService(RecommendationDeliveryRepository deliveries, Clock clock) {
        this.deliveries = deliveries; this.clock = clock;
    }

    @Transactional
    public List<DeliveryClaim> claimDue(int batchSize, java.time.Duration leaseDuration) {
        Instant now = clock.instant();
        return deliveries.findClaimable(now, batchSize).stream().map(delivery -> {
            delivery.claimed(now.plus(leaseDuration));
            return new DeliveryClaim(delivery.id(), delivery.payloadJson(), delivery.attemptCount());
        }).toList();
    }

    @Transactional
    public void delivered(UUID id, int status, String response) {
        deliveries.findById(id).ifPresent(delivery -> delivery.delivered(status, response, clock.instant()));
    }

    @Transactional
    public void retry(UUID id, Integer status, String code, Instant next) {
        deliveries.findById(id).ifPresent(delivery -> delivery.retry(status, code, next));
    }

    @Transactional
    public void terminal(UUID id, Integer status, String code) {
        deliveries.findById(id).ifPresent(delivery -> delivery.terminal(status, code, clock.instant()));
    }
}
