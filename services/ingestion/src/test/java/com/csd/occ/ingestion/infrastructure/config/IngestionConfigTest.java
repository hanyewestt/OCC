package com.csd.occ.ingestion.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.csd.occ.ingestion.domain.PollTargetService;
import com.csd.occ.ingestion.domain.ReferenceTopologyUnavailableException;
import org.junit.jupiter.api.Test;

class IngestionConfigTest {
    @Test
    void missingReferenceDefersSeedingWithoutStoppingTheApi() {
        var targets = mock(PollTargetService.class);
        doThrow(new ReferenceTopologyUnavailableException()).when(targets).seed();
        assertThatCode(() -> new IngestionConfig().seedPollTargets(targets).run())
                .doesNotThrowAnyException();
    }
}
