package com.csd.occ.ingestion.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DemoPageController.class)
class DemoPageControllerTest {
    @Autowired MockMvc mvc;

    @Test
    void servesDemoPageWithSimulationResultsHiddenUntilRunStarts() throws Exception {
        mvc.perform(get("/demo.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Show demo")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<section id=\"simulation-results\" hidden>")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "clearSimulationResults()")));
    }
}
