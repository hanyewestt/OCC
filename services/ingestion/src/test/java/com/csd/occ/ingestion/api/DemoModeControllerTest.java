package com.csd.occ.ingestion.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.ingestion.domain.DemoModeService;
import com.csd.occ.ingestion.domain.DemoRunService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = DemoModeController.class, properties = "occ.ingestion.demo.enabled=true")
class DemoModeControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean DemoModeService mode;
    @MockitoBean DemoRunService runs;

    @Test
    void runtimeModeCanBeReadAndEnabledOverHttp() throws Exception {
        when(mode.enabled()).thenReturn(false);
        when(runs.setMode(true)).thenReturn(true);

        mvc.perform(get("/api/v1/demo-mode"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(put("/api/v1/demo-mode").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));

        verify(runs).setMode(true);
    }

    @Test
    void runtimeModeRejectsMissingEnabledValue() throws Exception {
        mvc.perform(put("/api/v1/demo-mode").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":null}"))
                .andExpect(status().isBadRequest());
    }
}
