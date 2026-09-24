package com.csd.occ.bunching.infrastructure.ingestion;

import com.csd.occ.bunching.domain.BunchingEvaluation;
import com.csd.occ.bunching.domain.BunchingPolicy;
import com.csd.occ.bunching.domain.InvalidArrivalException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StoredArrivalMapperTest {
    private static final Instant NOW = Instant.parse("2026-09-22T04:00:00Z");
    private static final UUID REFERENCE = new UUID(0, 3);
    private final StoredArrivalMapper mapper = new StoredArrivalMapper(JsonMapper.builder().build());

    @Test
    void mapsOffsetTimestampsAndArrivalSlotsFromStoredPayload() {
        var result = mapper.map(source(payload("1", "1")), List.of(route(1)));
        assertEquals(NOW.plusSeconds(300), result.arrivals().getFirst().estimatedArrivalAt().orElseThrow());
        assertEquals(2, result.arrivals().get(1).visitOrder());
        assertEquals(1, result.arrivals().get(1).directionId().orElseThrow());
        assertEquals(REFERENCE, result.referenceVersionId().orElseThrow());
        assertEquals(BunchingEvaluation.State.BUNCHING, new BunchingPolicy()
                .evaluate(result, NOW, Duration.ofSeconds(90), Duration.ofSeconds(120)).groups().getFirst().state());
        assertEquals(result, mapper.map(source(payload("1", "1")), List.of(route(1))));
    }

    @Test
    void missingTopologyPreservesUnknownDirectionAndReference() {
        var result = mapper.map(source(payload("1", "1")), List.of());
        assertTrue(result.referenceVersionId().isEmpty());
        assertTrue(result.arrivals().stream().allMatch(arrival -> arrival.directionId().isEmpty()));
    }

    @Test
    void ambiguousRouteMappingDoesNotGuessADirection() {
        var result = mapper.map(source(payload("1", "1")), List.of(route(1), route(2)));
        assertTrue(result.arrivals().stream().allMatch(arrival -> arrival.directionId().isEmpty()));
    }

    @Test
    void visitNumberIsNotUsedAsDirectionOrArrivalSlot() {
        var result = mapper.map(source(payload("2", "2")), List.of(route(1)));
        assertEquals(1, result.arrivals().getFirst().visitOrder());
        assertTrue(result.arrivals().stream().allMatch(arrival -> arrival.directionId().isEmpty()));
    }

    @Test
    void malformedTimeAndMismatchedStopAreRejected() {
        assertThrows(InvalidArrivalException.class, () -> mapper.map(
                source(payload("1", "1").replace("2026-09-22T12:05:00+08:00", "bad-time")), List.of(route(1))));
        assertThrows(InvalidArrivalException.class, () -> mapper.map(
                source(payload("1", "1").replace("44009", "99999")), List.of(route(1))));
    }

    @Test
    void emptyTrailingBusDoesNotInvalidateTwoAvailableArrivals() {
        assertEquals(2, mapper.map(source(payload("1", "1")), List.of(route(1))).arrivals().size());
    }

    @Test
    void missingLeadingEstimateIsNotJoinedAcross() {
        String broken = payload("1", "1").replace("2026-09-22T12:05:00+08:00", "");
        assertEquals("INCOMPLETE_ARRIVAL_SEQUENCE", assertThrows(InvalidArrivalException.class,
                () -> mapper.map(source(broken), List.of(route(1)))).getMessage());
    }

    @ParameterizedTest
    @CsvSource({"0,true", "1,true", "2,false", "3,true", "4,false", "5,false", "6,false", "7,true"})
    void rawAndNormalizedPathsEnforceTheSameSlotSequence(int mask, boolean valid) {
        var json = JsonMapper.builder().build();
        var root = json.createObjectNode().put("BusStopCode", "44009");
        var service = root.putArray("Services").addObject().put("ServiceNo", "190").put("Operator", "SMRT");
        var rows = new ArrayList<Map<String, Object>>();
        for (int slot = 1; slot <= 3; slot++) {
            boolean present = (mask & (1 << (slot - 1))) != 0;
            String estimate = present ? NOW.plusSeconds(300 + (slot - 1) * 30).toString() : "";
            service.putObject(slot == 1 ? "NextBus" : "NextBus" + slot)
                    .put("OriginCode", "44009").put("DestinationCode", "10009")
                    .put("VisitNumber", "1").put("EstimatedArrival", estimate);
            if (present) rows.add(Map.of("id", new UUID(0, 20 + slot).toString(),
                    "raw_observation_id", new UUID(0, 1).toString(), "poll_run_id", new UUID(0, 2).toString(),
                    "reference_version_id", REFERENCE.toString(), "stop_code", "44009", "service_id", "190",
                    "visit_order", slot, "direction_id", 1, "estimated_arrival_at", estimate, "fetched_at", NOW.toString()));
        }
        for (String normalized : List.of("[]", json.writeValueAsString(rows))) {
            var input = new StoredArrivalMapper.StoredSnapshot(new UUID(0, 1), new UUID(0, 2), NOW,
                    "stop:44009", json.writeValueAsString(root), normalized);
            if (valid) {
                assertDoesNotThrow(() -> mapper.map(input, List.of(route(1))));
            } else {
                assertEquals("INCOMPLETE_ARRIVAL_SEQUENCE", assertThrows(InvalidArrivalException.class,
                        () -> mapper.map(input, List.of(route(1)))).getMessage());
            }
        }
    }

    @Test
    void validEmptyServicesProduceAnEmptySnapshot() {
        assertTrue(mapper.map(source("{\"BusStopCode\":\"44009\",\"Services\":[]}"), List.of()).arrivals().isEmpty());
    }

    private StoredArrivalMapper.StoredSnapshot source(String payload) {
        return new StoredArrivalMapper.StoredSnapshot(new UUID(0, 1), new UUID(0, 2), NOW,
                "stop:44009", payload, "[]");
    }

    private StoredArrivalMapper.RouteDirection route(int direction) {
        return new StoredArrivalMapper.RouteDirection(REFERENCE, "190", "SMRT", direction,
                "44009", "10009", "44009", 1);
    }

    private String payload(String firstVisit, String secondVisit) {
        return """
                {"BusStopCode":"44009","Services":[{"ServiceNo":"190","Operator":"SMRT",
                  "NextBus":{"OriginCode":"44009","DestinationCode":"10009","VisitNumber":"%s",
                             "EstimatedArrival":"2026-09-22T12:05:00+08:00"},
                  "NextBus2":{"OriginCode":"44009","DestinationCode":"10009","VisitNumber":"%s",
                              "EstimatedArrival":"2026-09-22T12:06:00+08:00"},
                  "NextBus3":{"EstimatedArrival":""}}]}
                """.formatted(firstVisit, secondVisit);
    }
}
