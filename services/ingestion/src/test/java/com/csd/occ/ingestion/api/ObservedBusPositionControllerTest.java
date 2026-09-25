package com.csd.occ.ingestion.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.ingestion.domain.CurrentBusArrivalView;
import com.csd.occ.ingestion.domain.IngestionQueryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ObservedBusPositionController.class)
class ObservedBusPositionControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean IngestionQueryService queries;

    @Test
    void mapReturnsAnUnverifiedObservationWithProvenanceAndFreshness() throws Exception {
        CurrentBusArrivalView position = mock(CurrentBusArrivalView.class);
        UUID observationId = UUID.randomUUID();
        UUID rawId = UUID.randomUUID();
        Instant fetchedAt = Instant.parse("2026-09-25T00:00:00Z");
        Instant asOf = fetchedAt.plusSeconds(60);
        when(position.getId()).thenReturn(observationId);
        when(position.getRawObservationId()).thenReturn(rawId);
        when(position.getStopCode()).thenReturn("44251");
        when(position.getServiceId()).thenReturn("190");
        when(position.getDirectionId()).thenReturn(1);
        when(position.getVisitOrder()).thenReturn(1);
        when(position.getLatitude()).thenReturn(new BigDecimal("1.3786545"));
        when(position.getLongitude()).thenReturn(new BigDecimal("103.7584354"));
        when(position.getFetchedAt()).thenReturn(fetchedAt);
        when(queries.now()).thenReturn(asOf);
        when(queries.mapPositions("190", PageRequest.of(0, 50)))
                .thenReturn(new PageImpl<>(List.of(position), PageRequest.of(0, 50), 1));
        when(queries.isStale(fetchedAt, asOf)).thenReturn(false);

        mvc.perform(get("/api/v1/map/observed-buses").param("serviceId", "190"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].observationId").value(observationId.toString()))
                .andExpect(jsonPath("$.items[0].rawObservationId").value(rawId.toString()))
                .andExpect(jsonPath("$.items[0].latitude").value(1.3786545))
                .andExpect(jsonPath("$.items[0].classification").value("OBSERVATION_ONLY"))
                .andExpect(jsonPath("$.items[0].identityStatus").value("UNVERIFIED"))
                .andExpect(jsonPath("$.items[0].stale").value(false))
                .andExpect(jsonPath("$.items[0].plate").doesNotExist());
    }
}
