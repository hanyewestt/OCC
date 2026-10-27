package com.csd.occ.ingestion.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.ingestion.domain.DemoModeService;
import com.csd.occ.ingestion.domain.DemoRunService;
import com.csd.occ.ingestion.infrastructure.security.IngestionSecurityConfig;
import com.csd.occ.ingestion.infrastructure.security.SupabaseRoleConverter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DemoModeController.class)
@Import({IngestionSecurityConfig.class, SupabaseRoleConverter.class})
class DemoModeControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean DemoModeService mode;
    @MockitoBean DemoRunService runs;

    @Test
    void backendDoesNotHostDemoPageAndDemoApiRequiresAnAuthorizedUser() throws Exception {
        mvc.perform(get("/demo.html").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/demo-mode"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void runtimeModeCanBeReadAndEnabledOverHttp() throws Exception {
        when(mode.enabled()).thenReturn(false);
        when(runs.setMode(true)).thenReturn(true);

        mvc.perform(get("/api/v1/demo-mode").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(put("/api/v1/demo-mode").with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));

        verify(runs).setMode(true);
    }

    @Test
    void runtimeModeRejectsMissingEnabledValue() throws Exception {
        mvc.perform(put("/api/v1/demo-mode").with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":null}"))
                .andExpect(status().isBadRequest());
    }
}
