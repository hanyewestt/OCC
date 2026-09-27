package com.csd.occ.diagnosis.domain;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiagnosisRequestService {
    public static final String SCHEMA_VERSION = "root-cause-diagnosis-v1";
    private static final String BUNCHING_PRODUCER = "bus-bunching-detection";
    private final DiagnosisRequestRepository requests;
    private final Clock clock;

    public DiagnosisRequestService(DiagnosisRequestRepository requests, Clock clock) {
        this.requests = requests; this.clock = clock;
    }

    @Transactional
    public DiagnosisRequest enqueue(EventType eventType, String producer, UUID eventId, UUID idempotencyKey) {
        if (eventType != EventType.BUS_BUNCHING)
            throw new UnsupportedEventTypeException("Event type is recognized but has no configured fact/evidence adapter");
        if (!BUNCHING_PRODUCER.equals(producer)) throw new IllegalArgumentException("Producer does not own BUS_BUNCHING events");
        UUID id = idempotencyKey == null ? UUID.randomUUID() : idempotencyKey;
        DiagnosisRequest existingId = requests.findById(id).orElse(null);
        if (existingId != null) {
            if (sameRequest(existingId, eventType, producer, eventId)) return existingId;
            throw new IdempotencyConflictException();
        }
        DiagnosisRequest existingEvent = requests.findByEventTypeAndProducerAndEventIdAndSchemaVersion(
                eventType.name(), producer, eventId, SCHEMA_VERSION).orElse(null);
        if (existingEvent != null) return existingEvent;

        requests.insertIfAbsent(id, eventType.name(), producer, eventId, SCHEMA_VERSION, clock.instant());
        DiagnosisRequest createdByKey = requests.findById(id).orElse(null);
        if (createdByKey != null) {
            if (sameRequest(createdByKey, eventType, producer, eventId)) return createdByKey;
            throw new IdempotencyConflictException();
        }
        return requests.findByEventTypeAndProducerAndEventIdAndSchemaVersion(
                eventType.name(), producer, eventId, SCHEMA_VERSION).orElseThrow(IdempotencyConflictException::new);
    }

    private boolean sameRequest(DiagnosisRequest existing, EventType eventType, String producer, UUID eventId) {
        return existing.eventId().equals(eventId) && existing.eventType().equals(eventType.name())
                && existing.producer().equals(producer) && existing.schemaVersion().equals(SCHEMA_VERSION);
    }

    @Transactional
    public List<UUID> claimDueRequests(int batchSize, Duration leaseDuration) {
        Instant now = clock.instant();
        return requests.findClaimable(now, batchSize).stream().map(request -> {
            request.claim(now.plus(leaseDuration), now);
            return request.id();
        }).toList();
    }

    public static final class UnsupportedEventTypeException extends RuntimeException {
        public UnsupportedEventTypeException(String message) { super(message); }
    }
    public static final class IdempotencyConflictException extends RuntimeException { }
}
