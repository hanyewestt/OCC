package com.csd.occ.planning.diagnosis.infrastructure.scheduling;

import static org.mockito.Mockito.*;

import com.csd.occ.planning.advisory.domain.HazardAdvisoryProcessor;
import com.csd.occ.planning.diagnosis.domain.DiagnosisRequestService;
import com.csd.occ.planning.diagnosis.domain.RecommendationDeliveryService;
import com.csd.occ.planning.diagnosis.domain.RecommendationSubmissionPort;
import com.csd.occ.planning.diagnosis.domain.RootCauseDiagnosisProcessor;
import com.csd.occ.planning.diagnosis.infrastructure.config.DiagnosisProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DiagnosisWorkerTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void queuedWorkUsesItsEventProcessorWithAutomaticBunchingOff(boolean hazard) {
        var requests = mock(DiagnosisRequestService.class);
        var bunching = mock(RootCauseDiagnosisProcessor.class);
        var hazards = mock(HazardAdvisoryProcessor.class);
        var deliveries = mock(RecommendationDeliveryService.class);
        var properties = mock(DiagnosisProperties.class);
        UUID id = UUID.randomUUID();
        when(properties.leaseDuration()).thenReturn(Duration.ofMinutes(5));
        when(requests.claimDueRequests(1, Duration.ofMinutes(5), false)).thenReturn(List.of(id));
        when(hazards.processIfHazard(id)).thenReturn(hazard);
        var worker = new DiagnosisWorker(requests, bunching, deliveries, mock(RecommendationSubmissionPort.class),
                properties, Clock.systemUTC(), hazards);

        worker.processDueWork();

        verify(hazards).processIfHazard(id);
        if (hazard) verifyNoInteractions(bunching);
        else verify(bunching).process(id);
    }
}
