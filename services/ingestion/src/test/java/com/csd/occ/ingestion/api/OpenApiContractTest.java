package com.csd.occ.ingestion.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.ingestion.domain.BunchingFeedService;
import com.csd.occ.ingestion.domain.IngestionQueryService;
import com.csd.occ.ingestion.domain.ReferenceTopologyQueryService;
import com.csd.occ.ingestion.infrastructure.config.OpenApiConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;

@WebMvcTest({ArrivalController.class, BunchingSnapshotController.class,
        ObservedBusPositionController.class, ReferenceTopologyController.class})
@Import(OpenApiConfig.class)
@ImportAutoConfiguration({SpringDocConfigProperties.class, SpringDocConfiguration.class,
        SpringDocWebMvcConfiguration.class})
class OpenApiContractTest {
    @Autowired MockMvc mvc;
    @MockitoBean IngestionQueryService ingestion;
    @MockitoBean BunchingFeedService bunching;
    @MockitoBean ReferenceTopologyQueryService topology;

    @Test
    void generatedContractIncludesObservedMapPositions() throws Exception {
        String document = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(document).contains("/api/v1/map/observed-buses", "listObservedBusPositions",
                "ObservedBusPositionResponse");
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "generated-openapi.json"), document);
    }
}
