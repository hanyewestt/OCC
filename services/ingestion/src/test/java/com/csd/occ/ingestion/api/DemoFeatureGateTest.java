package com.csd.occ.ingestion.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {
        DemoPageController.class,
        DemoRunController.class,
        DemoModeController.class,
        DemoSnapshotController.class,
        LiveBunchingAlertController.class
}, properties = "occ.ingestion.demo.enabled=false")
class DemoFeatureGateTest {
    @Autowired MockMvc mvc;

    @Test
    void demoPageAndApisAreAbsentWhenFeatureIsDisabled() throws Exception {
        mvc.perform(get("/demo.html")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/demo-mode")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/demo-runs/active")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/live-bunching-alerts")).andExpect(status().isNotFound());
        mvc.perform(get("/internal/v1/demo-runs/00000000-0000-0000-0000-000000000000/arrival-snapshots"))
                .andExpect(status().isNotFound());
    }
}
