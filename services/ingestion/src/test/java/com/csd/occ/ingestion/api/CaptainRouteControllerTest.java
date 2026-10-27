package com.csd.occ.ingestion.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csd.occ.ingestion.api.error.GlobalExceptionHandler;
import com.csd.occ.ingestion.domain.CaptainRoute;
import com.csd.occ.ingestion.domain.CaptainRouteService;
import com.csd.occ.ingestion.domain.ReferenceTopologyPort;
import com.csd.occ.ingestion.infrastructure.security.IngestionSecurityConfig;
import com.csd.occ.ingestion.infrastructure.security.SupabaseRoleConverter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CaptainRouteController.class)
@Import({GlobalExceptionHandler.class, IngestionSecurityConfig.class, SupabaseRoleConverter.class})
class CaptainRouteControllerTest {
    private static final String PATH = "/api/v1/captain-routes/current";
    private static final String CAPTAIN = "11111111-1111-4111-8111-111111111111";
    private static final Instant ARRIVAL = Instant.parse("2026-10-27T14:01:00Z");

    @Autowired MockMvc mvc;
    @MockitoBean CaptainRouteService routes;

    @Test
    void onlyCaptainsCanReadTheirRoute() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        for (String role : List.of("CONTROLLER", "ADMIN", "UNASSIGNED", "DRIVER")) {
            mvc.perform(get(PATH).with(user(CAPTAIN).roles(role))).andExpect(status().isForbidden());
        }
        verify(routes, never()).current(anyString());
    }

    @Test
    void captainWithoutAnAssignedBusGetsNoContent() throws Exception {
        when(routes.current(CAPTAIN)).thenReturn(Optional.empty());

        mvc.perform(get(PATH).with(user(CAPTAIN).roles("CAPTAIN"))).andExpect(status().isNoContent());
    }

    @Test
    void routeIsReadForTheTokenSubjectAndShowsPositionAndStopTimes() throws Exception {
        var stops = List.of(
                new CaptainRoute.Stop(stop(7, "44141", "Phoenix Stn"), Optional.empty(), Optional.empty()),
                new CaptainRoute.Stop(stop(8, "44251", "Bt Panjang Stn/Blk 604"), Optional.of(ARRIVAL),
                        Optional.of(CaptainRoute.ArrivalKind.PREDICTED)),
                new CaptainRoute.Stop(stop(9, "44241", "Opp Lompang Rd"), Optional.of(ARRIVAL.plusSeconds(120)),
                        Optional.of(CaptainRoute.ArrivalKind.ESTIMATED)));
        when(routes.current(CAPTAIN)).thenReturn(Optional.of(new CaptainRoute("demo-190-b", "SBS5898U", "190", 1, stops,
                new CaptainRoute.Position(CaptainRoute.Position.Source.SIMULATION, Optional.of(7), Optional.of(8),
                        Optional.of(ARRIVAL.minusSeconds(65))))));

        mvc.perform(get(PATH).with(user(CAPTAIN).roles("CAPTAIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.classification").value("SIMULATION"))
                .andExpect(jsonPath("$.bus.registrationNumber").value("SBS5898U"))
                .andExpect(jsonPath("$.bus.serviceId").value("190"))
                .andExpect(jsonPath("$.stops.length()").value(3))
                .andExpect(jsonPath("$.stops[0].arrivalAt").isEmpty())
                .andExpect(jsonPath("$.stops[1].description").value("Bt Panjang Stn/Blk 604"))
                .andExpect(jsonPath("$.stops[1].arrivalKind").value("PREDICTED"))
                .andExpect(jsonPath("$.stops[2].arrivalAt").value("2026-10-27T14:03:00Z"))
                .andExpect(jsonPath("$.stops[2].arrivalKind").value("ESTIMATED"))
                .andExpect(jsonPath("$.position.source").value("SIMULATION"))
                .andExpect(jsonPath("$.position.lastStopSequence").value(7))
                .andExpect(jsonPath("$.position.nextStopSequence").value(8));
        verify(routes).current(CAPTAIN);
    }

    private static ReferenceTopologyPort.BusRouteStop stop(int sequence, String code, String description) {
        return new ReferenceTopologyPort.BusRouteStop(code, "Road", description, BigDecimal.ONE, BigDecimal.ONE,
                sequence, new BigDecimal(sequence));
    }
}
