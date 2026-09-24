package com.csd.occ.ingestion.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.ingestion.domain.ReferenceTopologyPort;
import com.csd.occ.ingestion.domain.ReferenceTopologyQueryService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReferenceTopologyController.class)
class ReferenceTopologyControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ReferenceTopologyQueryService queries;

    @Test
    void busRoutesReturnAPaginatedEnvelope() throws Exception {
        when(queries.busRoutes(PageRequest.of(0, 50))).thenReturn(new PageImpl<>(
                List.of(new ReferenceTopologyPort.BusRouteSummary("190", "SBST", "TRUNK")),
                PageRequest.of(0, 50), 1));

        mvc.perform(get("/api/v1/bus-routes"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].serviceId").value("190"))
                .andExpect(jsonPath("$.items[0].operator").value("SBST"));
    }

    @Test
    void busRouteReturnsBothDirectionsWithOrderedStops() throws Exception {
        ReferenceTopologyPort.BusRouteStop first = new ReferenceTopologyPort.BusRouteStop("44009", "Fixture Road",
                "First stop", new BigDecimal("1.3"), new BigDecimal("103.8"), 1, BigDecimal.ZERO);
        ReferenceTopologyPort.BusRouteStop second = new ReferenceTopologyPort.BusRouteStop("44019", "Fixture Road",
                "Second stop", new BigDecimal("1.31"), new BigDecimal("103.81"), 2, new BigDecimal("0.5"));
        ReferenceTopologyPort.BusRoute route = new ReferenceTopologyPort.BusRoute("190", List.of(
                new ReferenceTopologyPort.BusRouteOperator("SBST", "TRUNK", List.of(
                        new ReferenceTopologyPort.BusRouteDirection(1, List.of(first, second)),
                        new ReferenceTopologyPort.BusRouteDirection(2, List.of(second, first))))));
        when(queries.busRoute("190")).thenReturn(java.util.Optional.of(route));

        mvc.perform(get("/api/v1/bus-routes/190"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.serviceId").value("190"))
                .andExpect(jsonPath("$.operators[0].directions[0].directionId").value(1))
                .andExpect(jsonPath("$.operators[0].directions[0].stops[1].stopSequence").value(2))
                .andExpect(jsonPath("$.operators[0].directions[1].directionId").value(2));
    }

    @Test
    void busStopsAndBusStopByCodeReturnStaticStopData() throws Exception {
        ReferenceTopologyPort.BusStop stop = new ReferenceTopologyPort.BusStop("44009", "Fixture Road", "Fixture Stop",
                new BigDecimal("1.3"), new BigDecimal("103.8"));
        when(queries.busStops(PageRequest.of(0, 50))).thenReturn(new PageImpl<>(List.of(stop), PageRequest.of(0, 50), 1));
        when(queries.busStop("44009")).thenReturn(java.util.Optional.of(stop));

        mvc.perform(get("/api/v1/bus-stops")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].stopCode").value("44009"));
        mvc.perform(get("/api/v1/bus-stops/44009")).andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Fixture Stop"));
    }

    @Test
    void missingRouteAndStopReturnNotFound() throws Exception {
        when(queries.busRoute("missing")).thenReturn(java.util.Optional.empty());
        when(queries.busStop("missing")).thenReturn(java.util.Optional.empty());

        mvc.perform(get("/api/v1/bus-routes/missing")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/bus-stops/missing")).andExpect(status().isNotFound());
    }
}
