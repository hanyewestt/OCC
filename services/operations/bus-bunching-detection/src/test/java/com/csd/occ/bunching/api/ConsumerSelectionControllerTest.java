package com.csd.occ.bunching.api;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.bunching.domain.ConsumerSelectionService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ConsumerSelectionController.class)
class ConsumerSelectionControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ConsumerSelectionService selections;

    @Test
    void acceptsAValidRunScopedDemoSelection() throws Exception {
        UUID runId = UUID.randomUUID();

        mvc.perform(put("/internal/v1/consumer-selection").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"DEMO\",\"demoRunId\":\"" + runId + "\"}"))
                .andExpect(status().isNoContent());

        verify(selections).select("DEMO", runId);
    }

    @Test
    void rejectsInvalidModeAndRunIdCombinationsWithoutChangingSelection() throws Exception {
        mvc.perform(put("/internal/v1/consumer-selection").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"DEMO\",\"demoRunId\":null}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/internal/v1/consumer-selection").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"LIVE\",\"demoRunId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/internal/v1/consumer-selection").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"MIXED\",\"demoRunId\":null}"))
                .andExpect(status().isBadRequest());

        verify(selections, never()).select(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.nullable(UUID.class));
    }
}
