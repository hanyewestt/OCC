package com.csd.occ.ingestion.infrastructure.datamall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.csd.occ.ingestion.domain.HazardKind;
import com.csd.occ.ingestion.infrastructure.config.HazardFeedProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

class DataMallHazardAdapterTest {
    private final DataMallAdapter client = mock(DataMallAdapter.class);
    private final HazardFeedProperties.Feed feed = new HazardFeedProperties.Feed(false, Duration.ofMinutes(3),
            Duration.ZERO, Duration.ofMinutes(10));
    private DataMallHazardAdapter adapter(int pageSize) {
        return new DataMallHazardAdapter(client, JsonMapper.builder().build(),
                new HazardFeedProperties(feed, feed, feed, pageSize, 2, ZoneId.of("Asia/Singapore")));
    }

    @Test
    void preservesFloodCoverageExpiryRestrictionsAndCancellationReferences() {
        when(client.get("/PubFloodAlerts")).thenReturn(ResponseEntity.ok("""
            {"value":[{"alertId":"flood-1","msgType":"Alert","dateTime":"2026-10-25T10:00:00+08:00",
            "expires":"2026-10-25T11:00:00+08:00","severity":"Severe","headline":"Flood",
            "description":"Avoid this road","instruction":"Avoid the area","circle":"1.3,103.8 0.5"},
            {"alertId":"cancel-1","msgType":"Cancel","references":"flood-1"}]}
            """));
        var result = adapter(500).fetch(HazardKind.FLOOD);
        assertThat(result.errors()).isEmpty();
        assertThat(result.facts()).hasSize(2);
        var flood = result.facts().getFirst();
        assertThat(flood.instruction()).isEqualTo("Avoid the area");
        assertThat(flood.effectiveUntil()).isEqualTo(Instant.parse("2026-10-25T03:00:00Z"));
        assertThat(flood.locations().getFirst().geometryRole()).isEqualTo("BROADCAST_COVERAGE");
        assertThat(result.facts().getLast().references()).containsExactly("flood-1");
    }

    @Test
    void preservesRoadOnlyLocationAndInclusiveSourceEndDate() {
        when(client.get("/RoadWorks")).thenReturn(ResponseEntity.ok("""
            {"value":[{"EventID":"works-1","StartDate":"2026-10-25","EndDate":"2026-10-25",
            "RoadName":"Teck Whye Ave","SvcDept":"Department","Other":"Approved works"}]}
            """));
        var fact = adapter(500).fetch(HazardKind.ROADWORKS).facts().getFirst();
        assertThat(fact.reportedAt()).isNull();
        assertThat(fact.effectiveUntil()).isEqualTo(Instant.parse("2026-10-25T16:00:00Z"));
        assertThat(fact.locations().getFirst().latitude()).isNull();
        assertThat(fact.department()).isEqualTo("Department");
    }

    @Test
    void preservesEarlierPagesAndMarksFailedLaterPageIncomplete() {
        when(client.get("/RoadWorks")).thenReturn(ResponseEntity.ok("""
            {"value":[{"EventID":"works-1","StartDate":"2026-10-25","EndDate":"2026-10-25","RoadName":"Road"}]}
            """));
        when(client.get("/RoadWorks?$skip=1")).thenThrow(new IllegalStateException("unavailable"));
        var result = adapter(1).fetch(HazardKind.ROADWORKS);
        assertThat(result.rawPages()).hasSize(1);
        assertThat(result.facts()).hasSize(1);
        assertThat(result.errors()).isNotEmpty();
    }

    @Test
    void retainsMultipleRailStationReferencesAndMissingMessageTime() {
        when(client.get("/TrainServiceAlerts")).thenReturn(ResponseEntity.ok("""
            {"value":{"Status":2,"AffectedSegments":[{"Line":"NEL","Direction":"Both","Stations":"NE1,NE3"},
            {"Line":"NEL","Direction":"Both","Stations":"NE5,NE6"}],"Message":[]}}
            """));
        var result = adapter(500).fetch(HazardKind.RAIL_DISRUPTION);
        assertThat(result.errors()).isEmpty();
        assertThat(result.facts().getFirst().locations()).extracting(location -> location.sourceReference())
                .containsExactly("NE1", "NE3", "NE5", "NE6");
        assertThat(result.facts().getFirst().reportedAt()).isNull();
    }

    @Test
    void preservesRailMessageAndSourceTimeWhenThereAreNoAffectedSegments() {
        when(client.get("/TrainServiceAlerts")).thenReturn(ResponseEntity.ok("""
            {"value":{"Status":2,"AffectedSegments":[],"Message":[
            {"Content":"Train service delayed","CreatedDate":"2026-10-25 10:00:00"}]}}
            """));

        var fact = adapter(500).fetch(HazardKind.RAIL_DISRUPTION).facts().getFirst();

        assertThat(fact.sourceEventKey()).isEqualTo("network");
        assertThat(fact.description()).isEqualTo("Train service delayed");
        assertThat(fact.reportedAt()).isEqualTo(Instant.parse("2026-10-25T02:00:00Z"));
        assertThat(fact.sourceAction()).isEqualTo("OBSERVED");
    }

    @Test
    void preservesRailRecoveryMessageAndTimeWhenThereAreNoAffectedSegments() {
        when(client.get("/TrainServiceAlerts")).thenReturn(ResponseEntity.ok("""
            {"value":{"Status":1,"AffectedSegments":[],"Message":[
            {"Content":"Normal train service resumed","CreatedDate":"2026-10-25 10:01:00"}]}}
            """));

        var fact = adapter(500).fetch(HazardKind.RAIL_DISRUPTION).facts().getFirst();

        assertThat(fact.sourceEventKey()).isEqualTo("network");
        assertThat(fact.description()).isEqualTo("Normal train service resumed");
        assertThat(fact.reportedAt()).isEqualTo(Instant.parse("2026-10-25T02:01:00Z"));
        assertThat(fact.sourceAction()).isEqualTo("RECOVERED");
    }

    @Test
    void malformedJsonIsRetainedButCannotBecomeACompleteEmptySnapshot() {
        when(client.get("/PubFloodAlerts")).thenReturn(ResponseEntity.ok("malformed"));
        var result = adapter(500).fetch(HazardKind.FLOOD);
        assertThat(result.rawPages()).containsExactly("malformed");
        assertThat(result.errors()).isNotEmpty();
    }
}
