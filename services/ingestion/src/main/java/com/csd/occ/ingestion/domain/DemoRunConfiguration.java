package com.csd.occ.ingestion.domain;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record DemoRunConfiguration(String serviceId, int directionId, int busCount, Duration dispatchGap,
                                   List<Duration> busGaps, String monitoredStopCode, DemoScenario scenario,
                                   List<UUID> captainUserIds) {
    public DemoRunConfiguration {
        serviceId = Objects.requireNonNull(serviceId).trim();
        dispatchGap = Objects.requireNonNull(dispatchGap);
        busGaps = List.copyOf(Objects.requireNonNull(busGaps));
        monitoredStopCode = Objects.requireNonNull(monitoredStopCode).trim();
        scenario = Objects.requireNonNull(scenario);
        captainUserIds = List.copyOf(Objects.requireNonNull(captainUserIds));
        if (serviceId.isBlank() || serviceId.length() > 20) throw new IllegalArgumentException("A bus service is required");
        if (directionId < 1) throw new IllegalArgumentException("Direction must be positive");
        if (busCount < 2 || busCount > 20) throw new IllegalArgumentException("A simulation injection must contain 2 to 20 buses");
        if (monitoredStopCode.isBlank() || monitoredStopCode.length() > 20) throw new IllegalArgumentException("A bus stop is required");
        if (dispatchGap.compareTo(Duration.ofSeconds(15)) < 0 || dispatchGap.compareTo(Duration.ofMinutes(15)) > 0)
            throw new IllegalArgumentException("Dispatch gap must be between 15 seconds and 15 minutes");
        if (busGaps.size() != busCount - 1) throw new IllegalArgumentException("One gap is required between each pair of buses");
        if (busGaps.stream().anyMatch(gap -> gap == null || gap.compareTo(Duration.ofSeconds(15)) < 0
                || gap.compareTo(Duration.ofMinutes(15)) > 0))
            throw new IllegalArgumentException("Each bus gap must be between 15 seconds and 15 minutes");
        if (!captainUserIds.isEmpty() && (captainUserIds.size() != busCount
                || captainUserIds.stream().distinct().count() != captainUserIds.size()))
            throw new IllegalArgumentException("Assign a distinct captain to every bus or leave all captains unassigned");
    }

    public DemoRunConfiguration(String serviceId, int directionId, int busCount, Duration dispatchGap,
                                List<Duration> busGaps, String monitoredStopCode, DemoScenario scenario) {
        this(serviceId, directionId, busCount, dispatchGap, busGaps, monitoredStopCode, scenario, List.of());
    }

    public DemoRunConfiguration(String serviceId, int directionId, int busCount, Duration dispatchGap,
                                DemoScenario scenario) {
        this(serviceId, directionId, busCount, dispatchGap,
                java.util.stream.IntStream.range(1, busCount).mapToObj(ignored -> dispatchGap).toList(), "44251", scenario, List.of());
    }

    public DemoRunConfiguration(String serviceId, int directionId, int busCount, Duration dispatchGap,
                                List<Duration> busGaps, DemoScenario scenario) {
        this(serviceId, directionId, busCount, dispatchGap, busGaps, "44251", scenario);
    }

    public static DemoRunConfiguration defaults() {
        return new DemoRunConfiguration("190", 1, 3, Duration.ofSeconds(90), DemoScenario.BUNCHING);
    }
}
