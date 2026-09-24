package com.csd.occ.ingestion.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.ingestion.domain.IngestionQueryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

@WebMvcTest(ArrivalController.class)
class ArrivalControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean IngestionQueryService queries;

    @Test
    void currentArrivalsReturnAPaginatedEnvelope() throws Exception {
        when(queries.currentArrivals("44009", "190", PageRequest.of(0, 50)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        mvc.perform(get("/api/v1/arrivals").param("stopCode", "44009").param("serviceId", "190"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(50));
    }

    @Test
    void historyRejectsAnInvertedTimeRange() throws Exception {
        mvc.perform(get("/api/v1/arrivals/history")
                        .param("from", "2026-09-25T00:00:00Z")
                        .param("to", "2026-09-24T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }
}
