package com.csd.occ.planning.diagnosis.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DiagnosisRequestService {
    public static final String SCHEMA_VERSION = "planning-event-v1";
    private static final String EVENT_PRODUCER = "disruption";
    private final DiagnosisRequestPersistenceService persistence;
    private final DiagnosisInputService inputs;
    private final EventSubmissionFingerprintPort fingerprints;

    public DiagnosisRequestService(DiagnosisRequestPersistenceService persistence, DiagnosisInputService inputs,
            EventSubmissionFingerprintPort fingerprints) {
        this.persistence = persistence;
        this.inputs = inputs;
        this.fingerprints = fingerprints;
    }

    /**
     * Enriches a producer-owned snapshot outside the database transaction, then persists it with the request.
     * Ingestion may be called for context; Disruption is never queried for newly submitted event facts.
     */
    public DiagnosisRequest enqueue(ProducerEventSubmission submission, UUID idempotencyKey,
            String suppliedFingerprint) {
        validate(submission);
        String computedFingerprint = fingerprints.fingerprint(submission);
        if (suppliedFingerprint == null || !suppliedFingerprint.matches("[0-9a-f]{64}")
                || !computedFingerprint.equals(suppliedFingerprint))
            throw new IllegalArgumentException("Event fingerprint does not match the submitted facts");

        UUID id = idempotencyKey == null ? UUID.randomUUID() : idempotencyKey;
        Optional<DiagnosisRequest> existing = persistence.findById(id);
        if (existing.isEmpty()) existing = persistence.findByEvent(
                submission.eventType().name(), submission.producer(), submission.eventId(), SCHEMA_VERSION);
        if (existing.isPresent()) {
            verifyExisting(existing.get(), submission);
            if (existing.get().inputSnapshot() != null) return existing.get();
        }

        String inputSnapshot = inputs.capture(submission);
        return persistence.enqueueProducer(submission, id, inputSnapshot);
    }

    /** Transitional entry point for requests created before producer snapshots were introduced. */
    public DiagnosisRequest enqueue(EventType eventType, String producer, UUID eventId, UUID idempotencyKey) {
        if (eventType != EventType.BUS_BUNCHING)
            throw new UnsupportedEventTypeException("Event type is recognized but has no configured fact/evidence adapter");
        if (!EVENT_PRODUCER.equals(producer)) throw new IllegalArgumentException("Producer does not own disruption events");

        UUID id = idempotencyKey == null ? UUID.randomUUID() : idempotencyKey;
        Optional<DiagnosisRequest> existing = persistence.findById(id);
        if (existing.isEmpty()) existing = persistence.findByEvent(
                eventType.name(), producer, eventId, SCHEMA_VERSION);
        if (existing.isPresent()) {
            if (!sameRequest(existing.get(), eventType, producer, eventId)) throw new IdempotencyConflictException();
            if (existing.get().inputSnapshot() != null) return existing.get();
        }

        String inputSnapshot = inputs.capture(eventId);
        return persistence.enqueueLegacy(eventType, producer, eventId, id, inputSnapshot);
    }

    private void verifyExisting(DiagnosisRequest existing, ProducerEventSubmission submission) {
        if (!sameRequest(existing, submission.eventType(), submission.producer(), submission.eventId()))
            throw new IdempotencyConflictException();
        if (existing.inputSnapshot() != null
                && !inputs.matchesProducerFacts(existing.inputSnapshot(), submission))
            throw new IdempotencyConflictException();
    }

    private void validate(ProducerEventSubmission submission) {
        if (submission == null || !SCHEMA_VERSION.equals(submission.schemaVersion())
                || submission.eventType() == null || !EVENT_PRODUCER.equals(submission.producer())
                || submission.eventId() == null || submission.disruptionId() == null
                || submission.classification() == null || submission.detectedAt() == null
                || submission.detectionRuleVersion() == null || submission.detectionRuleVersion().isBlank()
                || submission.evidenceIds() == null || submission.evidenceIds().isEmpty()
                || submission.evidenceIds().size() > 100
                || submission.evidenceIds().stream().anyMatch(java.util.Objects::isNull))
            throw new UnsupportedEventTypeException("Event submission is not a supported confirmed event");

        if (submission.eventType() == EventType.TRAFFIC_INCIDENT) {
            validateTrafficIncident(submission);
            return;
        }
        if (submission.eventType() == EventType.HAZARD) {
            var event = submission.hazardSnapshot();
            if (event == null || submission.eventSnapshot() != null || submission.trafficSnapshot() != null
                    || !PlanningScope.valid(submission.classification(), submission.demoRunId())
                    || !submission.eventId().equals(event.eventId()) || !submission.disruptionId().equals(event.disruptionId())
                    || !submission.classification().equals(event.classification())
                    || !java.util.Objects.equals(submission.demoRunId(), event.demoRunId())
                    || !submission.detectedAt().equals(event.eventAt()) || event.facts() == null
                    || !List.of("FLOOD", "RAIL_DISRUPTION", "ROADWORKS").contains(event.facts().kind())
                    || !List.of("OPENED", "UPDATED", "CLEARED").contains(event.action())
                    || !"hazard-observation-v1".equals(submission.detectionRuleVersion())
                    || !submission.evidenceIds().equals(List.of(event.sourceObservationId().toString())))
                throw new IllegalArgumentException("Hazard submission facts are inconsistent");
            return;
        }
        if (submission.eventType() != EventType.BUS_BUNCHING || submission.trafficSnapshot() != null || submission.hazardSnapshot() != null
                || !PlanningScope.valid(submission.classification(), submission.demoRunId())
                || submission.eventSnapshot() == null)
            throw new UnsupportedEventTypeException("Event submission is not a supported confirmed event");

        BunchingEventFacts event = submission.eventSnapshot();
        if (!submission.eventId().equals(event.eventId())
                || !submission.disruptionId().equals(event.bunchingEventId())
                || !submission.classification().equals(event.classification())
                || !java.util.Objects.equals(submission.demoRunId(), event.demoRunId())
                || !submission.detectionRuleVersion().equals(event.detectionRuleVersion())
                || !"CONFIRMED".equals(event.status()) || event.serviceId() == null
                || event.monitoredStopCode() == null || event.detectionRuleVersion() == null
                || event.detectionRuleVersion().isBlank() || event.firstConfirmedAt() == null
                || event.observedHeadwaySeconds().size() < 2 || event.confirmingObservationTimes().size() < 2
                || event.confirmingObservationIds().size() < 2
                || event.confirmingObservationIds().stream().anyMatch(java.util.Objects::isNull)
                || event.confirmingObservationIds().stream().distinct().count() < 2
                || event.evidenceIds().isEmpty() || !submission.evidenceIds().equals(event.evidenceIds())
                || !event.firstConfirmedAt().equals(event.confirmingObservationTimes().getFirst())
                || submission.detectedAt().isBefore(event.confirmingObservationTimes().getLast())
                || event.confirmingObservationTimes().stream().anyMatch(java.util.Objects::isNull)
                || event.confirmingObservationTimes().getLast().isBefore(event.firstConfirmedAt())) {
            throw new IllegalArgumentException("Event submission facts are inconsistent or incomplete");
        }
    }

    private void validateTrafficIncident(ProducerEventSubmission submission) {
        TrafficIncidentEventFacts event = submission.trafficSnapshot();
        if (event == null || submission.eventSnapshot() != null || submission.hazardSnapshot() != null
                || !submission.eventId().equals(event.eventId())
                || !submission.disruptionId().equals(event.disruptionId())
                || !submission.classification().equals(event.classification())
                || !java.util.Objects.equals(submission.demoRunId(), event.demoRunId())
                || !submission.detectedAt().equals(event.eventAt())
                || !"traffic-incident-observation-v1".equals(submission.detectionRuleVersion())
                || !submission.evidenceIds().equals(List.of(event.sourceObservationId().toString())))
            throw new IllegalArgumentException("Traffic incident event submission facts are inconsistent or incomplete");
    }

    private boolean sameRequest(DiagnosisRequest existing, EventType eventType, String producer, UUID eventId) {
        return existing.eventId().equals(eventId) && existing.eventType().equals(eventType.name())
                && existing.producer().equals(producer) && existing.schemaVersion().equals(SCHEMA_VERSION);
    }

    public List<UUID> claimDueRequests(int batchSize, Duration leaseDuration) {
        return persistence.claimDueRequests(batchSize, leaseDuration);
    }

    public static final class UnsupportedEventTypeException extends RuntimeException {
        public UnsupportedEventTypeException(String message) { super(message); }
    }

    public static final class IdempotencyConflictException extends RuntimeException { }
}
