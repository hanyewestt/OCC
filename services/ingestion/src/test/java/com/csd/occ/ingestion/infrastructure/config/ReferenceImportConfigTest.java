package com.csd.occ.ingestion.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.csd.occ.ingestion.domain.ReferenceImportService;
import com.csd.occ.ingestion.domain.ReferenceSourceUnavailableException;
import org.junit.jupiter.api.Test;

class ReferenceImportConfigTest {
    @Test
    void sourceFailureDoesNotStopTheApiButPersistenceFailuresStillFailStartup() {
        var imports = mock(ReferenceImportService.class);
        var runner = new ReferenceImportConfig().importBusReferenceTopology(imports);
        doThrow(new ReferenceSourceUnavailableException(new RuntimeException("synthetic 401")))
                .when(imports).refresh();
        assertThatCode(() -> runner.run()).doesNotThrowAnyException();
        doThrow(new IllegalStateException("synthetic database failure")).when(imports).refresh();
        assertThatThrownBy(() -> runner.run()).isInstanceOf(IllegalStateException.class);
    }
}
