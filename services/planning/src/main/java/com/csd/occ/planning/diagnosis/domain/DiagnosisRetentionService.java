package com.csd.occ.planning.diagnosis.domain;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class DiagnosisRetentionService {
    private final DiagnosisRequestRepository requests;
    private final RecommendationDeliveryRepository deliveries;
    private final Clock clock;
    public DiagnosisRetentionService(DiagnosisRequestRepository requests, RecommendationDeliveryRepository deliveries, Clock clock) {
        this.requests = requests; this.deliveries = deliveries; this.clock = clock;
    }
    @Transactional public long purge(Duration duration) {
        var cutoff = clock.instant().minus(duration);
        long deletedRows = deliveries.deleteCompletedBefore(cutoff);
        deletedRows += requests.deleteUnreferencedTerminalBefore(cutoff);
        return deletedRows;
    }
}
