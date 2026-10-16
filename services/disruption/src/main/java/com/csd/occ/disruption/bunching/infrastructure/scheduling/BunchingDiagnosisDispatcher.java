package com.csd.occ.disruption.bunching.infrastructure.scheduling;

import com.csd.occ.disruption.bunching.domain.DiagnosisOutboxClaim;
import com.csd.occ.disruption.bunching.domain.DiagnosisOutboxService;
import com.csd.occ.disruption.bunching.domain.PlanningEventSubmission;
import com.csd.occ.disruption.bunching.domain.PlanningEventSubmissionPort;
import com.csd.occ.disruption.bunching.domain.RetryBackoffPolicy;
import com.csd.occ.disruption.bunching.infrastructure.config.DiagnosisOutboxProperties;
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
@ConditionalOnProperty(prefix = "occ.disruption.planning", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class BunchingDiagnosisDispatcher {
    private static final String DIAGNOSIS_PATH = "/internal/v1/planning/requests";
    private final DiagnosisOutboxService outbox;
    private final DiagnosisOutboxProperties properties;
    private final RestClient client;
    private final Clock clock;
    private final PlanningEventSubmissionPort submissions;
    private final RetryBackoffPolicy retryBackoff = new RetryBackoffPolicy();

    public BunchingDiagnosisDispatcher(DiagnosisOutboxService outbox, DiagnosisOutboxProperties properties,
                                      @Qualifier("diagnosisRestClient") RestClient diagnosisRestClient, Clock clock,
                                      PlanningEventSubmissionPort submissions) {
        this.outbox = outbox; this.properties = properties; this.client = diagnosisRestClient; this.clock = clock;
        this.submissions = submissions;
    }

    @Scheduled(fixedDelayString = "${occ.disruption.planning.delivery-interval}")
    public void dispatchPending() {
        List<DiagnosisOutboxClaim> claims = outbox.claimDue(properties.batchSize(), properties.leaseDuration());
        claims.forEach(this::dispatch);
    }

    private void dispatch(DiagnosisOutboxClaim claim) {
        try {
            if (!PlanningEventSubmission.SCHEMA_VERSION.equals(claim.schemaVersion()) || claim.payloadJson() == null) {
                outbox.fail(claim.id(), null, "PLANNING_OUTBOX_PAYLOAD_INVALID");
                return;
            }
            String computedFingerprint = submissions.fingerprint(claim.payloadJson(), claim.eventId());
            if (claim.payloadFingerprint() == null) {
                String persisted = outbox.retainFingerprintIfAbsent(claim.id(), computedFingerprint);
                if (!computedFingerprint.equals(persisted)) {
                    outbox.fail(claim.id(), null, "PLANNING_OUTBOX_FINGERPRINT_MISMATCH");
                    return;
                }
            } else if (!computedFingerprint.equals(claim.payloadFingerprint())) {
                outbox.fail(claim.id(), null, "PLANNING_OUTBOX_FINGERPRINT_MISMATCH");
                return;
            }
            int status = client.post().uri(DIAGNOSIS_PATH).header("Idempotency-Key", claim.id().toString())
                    .header("X-OCC-Event-Fingerprint", computedFingerprint)
                    .header("Content-Type", "application/json")
                    .body(claim.payloadJson())
                    .exchange((request, response) -> response.getStatusCode().value());
            if (status == 202 || status == 200) {
                outbox.delivered(claim.id(), status);
            } else if (status == 408 || status == 429 || status >= 500) {
                retryOrFail(claim, status, "PLANNING_REQUEST_TRANSIENT_HTTP_FAILURE");
            } else {
                outbox.fail(claim.id(), status, "PLANNING_REQUEST_REJECTED_HTTP_" + status);
            }
        } catch (RuntimeException unavailable) {
            retryOrFail(claim, null, "PLANNING_REQUEST_UNAVAILABLE");
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

}
