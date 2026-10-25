package com.csd.occ.ingestion.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Deterministic synthetic facts sharing the existing service-190/972 replay geography. */
public final class DemoHazardScenario {
    private DemoHazardScenario() {}
    public static List<HazardFeedPort.Fact> observations(int step, Instant startedAt, Instant now) {
        if (step < 0 || step >= DemoReplayScenario.STEP_COUNT) throw new IllegalArgumentException("Invalid scenario step");
        var road = new HazardLocation("ROAD", "Teck Whye Ave", "Teck Whye Ave", null, null, null,
                "ROAD_NAME", "PROVIDER_SUPPLIED");
        var coverage = new HazardLocation("AREA", "demo-flood", "Teck Whye Ave", new BigDecimal("1.3802550"),
                new BigDecimal("103.7550750"), new BigDecimal("0.5"), "BROADCAST_COVERAGE", "PROVIDER_SUPPLIED");
        var station = new HazardLocation("STATION", "BP4", "Choa Chu Kang", null, null, null,
                "STATION_REFERENCE", "PROVIDER_SUPPLIED");
        return List.of(
                new HazardFeedPort.Fact(HazardKind.FLOOD, step == 2 ? "demo-flood-cancel" : "demo-flood",
                        step == 2 ? "CANCEL" : "OBSERVED", now, null, startedAt.plusSeconds(3600), "DEMO: Flood alert",
                        step == 0 ? "Synthetic flooding near Teck Whye Ave" : "Synthetic flood update",
                        "Severe", "Avoid the affected area", step == 2 ? List.of("demo-flood") : List.of(),
                        null, null, "Actual", null, List.of(coverage)),
                new HazardFeedPort.Fact(HazardKind.RAIL_DISRUPTION, "BPL|Both", step == 2 ? "RECOVERED" : "OBSERVED",
                        now, null, null, "DEMO: BPL disruption", "Synthetic rail disruption near connecting bus services",
                        null, null, List.of(), "BPL", "Both", step == 2 ? "1" : "2", null, List.of(station)),
                new HazardFeedPort.Fact(HazardKind.ROADWORKS, "demo-roadworks", "OBSERVED", null,
                        startedAt, step == 2 ? now : startedAt.plusSeconds(3600), "DEMO: Approved works",
                        "Synthetic approved roadworks; road closure not established", null, null, List.of(),
                        null, null, null, "DEMO department", List.of(road)));
    }
}
