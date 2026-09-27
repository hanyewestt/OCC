package com.csd.occ.ingestion.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.mockito.Mockito.when;

import com.csd.occ.ingestion.domain.BunchingControlPort;
import com.csd.occ.ingestion.domain.BunchingFeedService;
import com.csd.occ.ingestion.domain.DemoModeService;
import com.csd.occ.ingestion.domain.DemoRunService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {
        DemoPageController.class,
        DemoRunController.class,
        DemoModeController.class,
        DemoSnapshotController.class,
        LiveBunchingAlertController.class
})
class DemoFeatureGateTest {
    @Autowired MockMvc mvc;
    @MockitoBean DemoModeService mode;
    @MockitoBean DemoRunService runs;
    @MockitoBean BunchingFeedService feed;
    @MockitoBean BunchingControlPort bunching;

    @Test
    void demoPageAndApisRemainAvailableWhenThePersistedRuntimeModeIsDisabled() throws Exception {
        when(mode.enabled()).thenReturn(false);
        when(runs.active()).thenReturn(Optional.empty());

        mvc.perform(get("/demo.html")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/demo-mode")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(get("/api/v1/demo-runs/active")).andExpect(status().isNoContent());
    }
}
