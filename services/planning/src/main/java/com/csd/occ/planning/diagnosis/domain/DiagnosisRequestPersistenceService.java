package com.csd.occ.planning.diagnosis.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiagnosisRequestPersistenceService {
    private final DiagnosisRequestRepository requests;
    private final SnapshotCodecPort snapshots;
    private final Clock clock;

    public DiagnosisRequestPersistenceService(DiagnosisRequestRepository requests, SnapshotCodecPort snapshots,
            Clock clock) {
        this.requests = requests;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<DiagnosisRequest> findById(UUID id) {
        return requests.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<DiagnosisRequest> findByEvent(String eventType, String producer, UUID eventId,
            String schemaVersion) {
        return requests.findByEventTypeAndProducerAndEventIdAndSchemaVersion(eventType, producer, eventId,
                schemaVersion);
    }

    @Transactional
    public DiagnosisRequest enqueueProducer(ProducerEventSubmission submission, UUID id, String inputSnapshot) {
        DiagnosisRequest byId = requests.findById(id).orElse(null);
        if (byId != null) return retainProducerSnapshot(byId, submission, inputSnapshot);

        DiagnosisRequest byEvent = findProducerEvent(submission);
        if (byEvent != null) return retainProducerSnapshot(byEvent, submission, inputSnapshot);

        requests.insertIfAbsent(id, submission.eventType().name(), submission.producer(), submission.eventId(),
                DiagnosisRequestService.SCHEMA_VERSION, clock.instant());
        DiagnosisRequest createdById = requests.findById(id).orElse(null);
        if (createdById != null) return retainProducerSnapshot(createdById, submission, inputSnapshot);
        DiagnosisRequest createdByEvent = findProducerEvent(submission);
        if (createdByEvent != null) return retainProducerSnapshot(createdByEvent, submission, inputSnapshot);
        throw new DiagnosisRequestService.IdempotencyConflictException();
    }

    @Transactional
    public DiagnosisRequest enqueueLegacy(EventType eventType, String producer, UUID eventId, UUID id,
            String inputSnapshot) {
        DiagnosisRequest byId = requests.findById(id).orElse(null);
        if (byId != null) {
            if (!sameRequest(byId, eventType, producer, eventId))
                throw new DiagnosisRequestService.IdempotencyConflictException();
            if (byId.inputSnapshot() == null && inputSnapshot != null) byId.retainInput(inputSnapshot);
            return byId;
        }
        DiagnosisRequest byEvent = requests.findByEventTypeAndProducerAndEventIdAndSchemaVersion(
                eventType.name(), producer, eventId, DiagnosisRequestService.SCHEMA_VERSION).orElse(null);
        if (byEvent != null) {
            if (byEvent.inputSnapshot() == null && inputSnapshot != null) byEvent.retainInput(inputSnapshot);
            return byEvent;
        }
        requests.insertIfAbsent(id, eventType.name(), producer, eventId, DiagnosisRequestService.SCHEMA_VERSION,
                clock.instant());
        DiagnosisRequest createdById = requests.findById(id).orElse(null);
        if (createdById != null) {
            if (!sameRequest(createdById, eventType, producer, eventId))
                throw new DiagnosisRequestService.IdempotencyConflictException();
            if (createdById.inputSnapshot() == null && inputSnapshot != null) createdById.retainInput(inputSnapshot);
            return createdById;
        }
        return requests.findByEventTypeAndProducerAndEventIdAndSchemaVersion(eventType.name(), producer, eventId,
                DiagnosisRequestService.SCHEMA_VERSION).orElseThrow(DiagnosisRequestService.IdempotencyConflictException::new);
    }

    @Transactional
    public List<UUID> claimDueRequests(int batchSize, Duration leaseDuration) {
        Instant now = clock.instant();
        return requests.findClaimable(now, batchSize).stream().map(request -> {
            request.claim(now.plus(leaseDuration), now);
            return request.id();
        }).toList();
    }

    private DiagnosisRequest findProducerEvent(ProducerEventSubmission submission) {
        return requests.findByEventTypeAndProducerAndEventIdAndSchemaVersion(submission.eventType().name(),
                submission.producer(), submission.eventId(), DiagnosisRequestService.SCHEMA_VERSION).orElse(null);
    }

    private DiagnosisRequest retainProducerSnapshot(DiagnosisRequest existing, ProducerEventSubmission submission,
            String inputSnapshot) {
        if (!sameRequest(existing, submission.eventType(), submission.producer(), submission.eventId()))
            throw new DiagnosisRequestService.IdempotencyConflictException();
        if (existing.inputSnapshot() == null) {
            if (inputSnapshot == null) throw new DiagnosisRequestService.IdempotencyConflictException();
            existing.retainInput(inputSnapshot);
            return existing;
        }
        if (inputSnapshot != null && !sameProducerFacts(existing.inputSnapshot(), submission.eventSnapshot()))
            throw new DiagnosisRequestService.IdempotencyConflictException();
        return existing;
    }

    private boolean sameProducerFacts(String inputSnapshot, BunchingEventFacts event) {
        try {
            return snapshots.decode(inputSnapshot).event().equals(event);
        } catch (RuntimeException malformedSnapshot) {
            return false;
        }
    }

    private boolean sameRequest(DiagnosisRequest existing, EventType eventType, String producer, UUID eventId) {
        return existing.eventId().equals(eventId) && existing.eventType().equals(eventType.name())
                && existing.producer().equals(producer)
                && existing.schemaVersion().equals(DiagnosisRequestService.SCHEMA_VERSION);
    }
}
