package com.csd.occ.disruption.bunching.infrastructure.jackson;

import com.csd.occ.disruption.bunching.domain.AlertEvent;
import com.csd.occ.disruption.bunching.domain.BunchingEventSnapshot;
import com.csd.occ.disruption.bunching.domain.EncodedPlanningEventSubmission;
import com.csd.occ.disruption.bunching.domain.PlanningEventSubmission;
import com.csd.occ.disruption.bunching.domain.PlanningEventSubmissionPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JacksonPlanningEventSubmissionAdapter implements PlanningEventSubmissionPort {
    private final JsonMapper json;

    public JacksonPlanningEventSubmissionAdapter(JsonMapper json) {
        this.json = json;
    }

    @Override
    public EncodedPlanningEventSubmission encode(AlertEvent event) {
        if (!"OPENED".equals(event.action()) || !"SIMULATION".equals(event.classification())
                || event.demoRunId() == null || event.expectedHeadwaySeconds() == null
                || event.firstObservedHeadwaySeconds() == null || event.secondObservedHeadwaySeconds() == null
                || event.firstConfirmingAt() == null || event.secondConfirmingAt() == null
                || event.detectionRuleVersion() == null || event.confirmingObservationIds().size() != 4) {
            throw new IllegalArgumentException("Confirmed simulation event facts are incomplete");
        }
        List<Integer> headways = List.of(event.firstObservedHeadwaySeconds(), event.secondObservedHeadwaySeconds());
        List<java.time.Instant> times = List.of(event.firstConfirmingAt(), event.secondConfirmingAt());
        List<UUID> observations = event.confirmingObservationIds();
        List<String> evidenceIds = event.detectionEvidenceIds();
        BunchingEventSnapshot facts = new BunchingEventSnapshot(event.id(), event.alertId(), event.classification(),
                event.demoRunId(), event.serviceId(), event.directionId(), event.stopCode(),
                event.leadingObservationId(), event.followingObservationId(), "CONFIRMED",
                event.expectedHeadwaySeconds(), headways, event.firstConfirmingAt(), times, observations,
                evidenceIds, event.detectionRuleVersion());
        PlanningEventSubmission submission = new PlanningEventSubmission(PlanningEventSubmission.SCHEMA_VERSION,
                "BUS_BUNCHING", "disruption", event.id(), event.alertId(), event.classification(), event.demoRunId(),
                event.triggeredAt(), event.detectionRuleVersion(), evidenceIds, facts);
        String payload = json.writeValueAsString(submission);
        return new EncodedPlanningEventSubmission(payload, sha256(payload), PlanningEventSubmission.SCHEMA_VERSION);
    }

    @Override
    public String fingerprint(String payloadJson, UUID expectedEventId) {
        try {
            PlanningEventSubmission submission = json.readValue(payloadJson, PlanningEventSubmission.class);
            validate(submission, expectedEventId);
            return sha256(json.writeValueAsString(submission));
        } catch (JacksonException malformed) {
            throw new IllegalArgumentException("Persisted Planning event payload is invalid", malformed);
        }
    }

    private void validate(PlanningEventSubmission submission, UUID expectedEventId) {
        if (submission == null || !PlanningEventSubmission.SCHEMA_VERSION.equals(submission.schemaVersion())
                || !"BUS_BUNCHING".equals(submission.eventType()) || !"disruption".equals(submission.producer())
                || !Objects.equals(expectedEventId, submission.eventId()) || submission.disruptionId() == null
                || !"SIMULATION".equals(submission.classification()) || submission.demoRunId() == null
                || submission.detectedAt() == null || submission.eventSnapshot() == null
                || !Objects.equals(submission.eventId(), submission.eventSnapshot().eventId())
                || !Objects.equals(submission.disruptionId(), submission.eventSnapshot().bunchingEventId())
                || !Objects.equals(submission.classification(), submission.eventSnapshot().classification())
                || !Objects.equals(submission.demoRunId(), submission.eventSnapshot().demoRunId())
                || !Objects.equals(submission.detectionRuleVersion(), submission.eventSnapshot().detectionRuleVersion())
                || !Objects.equals(submission.evidenceIds(), submission.eventSnapshot().evidenceIds())) {
            throw new IllegalArgumentException("Persisted Planning event payload identity is invalid");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
