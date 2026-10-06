package com.csd.occ.bunching.infrastructure.scheduling;

import com.csd.occ.bunching.domain.DiagnosisOutboxClaim;
import com.csd.occ.bunching.domain.DiagnosisOutboxService;
import com.csd.occ.bunching.domain.RetryBackoffPolicy;
import com.csd.occ.bunching.infrastructure.config.DiagnosisOutboxProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(prefix = "occ.bunching.diagnosis", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class BunchingDiagnosisDispatcher {
    private static final String DIAGNOSIS_PATH = "/internal/v1/diagnosis-requests";
    private final DiagnosisOutboxService outbox;
    private final DiagnosisOutboxProperties properties;
    private final RestClient client;
    private final Clock clock;
    private final RetryBackoffPolicy retryBackoff = new RetryBackoffPolicy();

    public BunchingDiagnosisDispatcher(DiagnosisOutboxService outbox, DiagnosisOutboxProperties properties,
                                      @Qualifier("diagnosisRestClient") RestClient diagnosisRestClient, Clock clock) {
        this.outbox = outbox; this.properties = properties; this.client = diagnosisRestClient; this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${occ.bunching.diagnosis.delivery-interval}")
    public void dispatchPending() {
        List<DiagnosisOutboxClaim> claims = outbox.claimDue(properties.batchSize(), properties.leaseDuration());
        claims.forEach(this::dispatch);
    }

    private void dispatch(DiagnosisOutboxClaim claim) {
        try {
            int status = client.post().uri(DIAGNOSIS_PATH).header("Idempotency-Key", claim.id().toString())
                    .body(new DiagnosisRequest("BUS_BUNCHING", "bus-bunching-detection", claim.eventId()))
                    .exchange((request, response) -> response.getStatusCode().value());
            if (status == 202 || status == 200) {
                outbox.delivered(claim.id(), status);
            } else if (status == 408 || status == 429 || status >= 500) {
                retryOrFail(claim, status, "ROOT_CAUSE_DIAGNOSIS_TRANSIENT_HTTP_FAILURE");
            } else {
                outbox.fail(claim.id(), status, "ROOT_CAUSE_DIAGNOSIS_REJECTED_HTTP_" + status);
            }
        } catch (RuntimeException unavailable) {
            retryOrFail(claim, null, "ROOT_CAUSE_DIAGNOSIS_UNAVAILABLE");
        }
    }

    private void retryOrFail(DiagnosisOutboxClaim claim, Integer status, String code) {
        if (properties.maxAttempts() > 0 && claim.attemptNumber() >= properties.maxAttempts()) {
            outbox.fail(claim.id(), status, code);
            return;
        }
        double jitter = ThreadLocalRandom.current().nextDouble(-0.20, 0.2000001);
        Instant retryAt = clock.instant().plus(retryBackoff.delay(properties.retryInitialDelay(),
                properties.retryMaxDelay(), claim.attemptNumber(), jitter));
        outbox.retry(claim.id(), status, code, retryAt);
    }

    private record DiagnosisRequest(String eventType, String producer, UUID eventId) { }
}
