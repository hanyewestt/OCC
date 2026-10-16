package com.csd.occ.planning.diagnosis.domain;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DiagnosisInputServiceTest {
    @Test
    void producerSnapshotEnrichmentUsesIngestionWithoutFetchingFactsFromDisruption() {
        EventFactsPort disruptionFacts = mock(EventFactsPort.class);
        ContextEvidencePort ingestionContext = mock(ContextEvidencePort.class);
        SnapshotCodecPort codec = mock(SnapshotCodecPort.class);
        UUID eventId = UUID.randomUUID();
        UUID disruptionId = UUID.randomUUID();
        UUID demoRunId = UUID.randomUUID();
        UUID firstObservation = UUID.randomUUID();
        UUID secondObservation = UUID.randomUUID();
        Instant confirmedAt = Instant.parse("2026-09-28T00:00:00Z");
        BunchingEventFacts event = new BunchingEventFacts(eventId, disruptionId, "SIMULATION", demoRunId, "190", 1,
                "44009", firstObservation, secondObservation, "CONFIRMED", 600, List.of(360, 390), confirmedAt,
                List.of(confirmedAt, confirmedAt.plusSeconds(60)), List.of(firstObservation, secondObservation),
                List.of("event-evidence", "arrival-evidence"), "bunching-gap-v1");
        RootCauseContext context = new RootCauseContext("SIMULATION", demoRunId, UUID.randomUUID(), List.of(),
                List.of(), List.of());
        when(ingestionContext.findContext(event)).thenReturn(context);
        when(codec.encode(org.mockito.ArgumentMatchers.any(EvidenceSnapshot.class))).thenReturn("captured-snapshot");

        DiagnosisInputService inputs = new DiagnosisInputService(disruptionFacts, ingestionContext, codec);

        inputs.capture(event);

        verify(ingestionContext).findContext(event);
        verify(disruptionFacts, never()).findConfirmedEvent(eventId);
    }
}
