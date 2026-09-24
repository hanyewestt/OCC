package com.csd.occ.ingestion.domain;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

@Service
public class ArrivalPollingService {
    private final ArrivalTelemetryPort telemetry;
    private final ArrivalFeedPort feed;
    private final Clock clock;

    public ArrivalPollingService(ArrivalTelemetryPort telemetry, ArrivalFeedPort feed, Clock clock) {
        this.telemetry = telemetry;
        this.feed = feed;
        this.clock = clock;
    }

    public Optional<Completion> poll(ArrivalPollTarget target, Duration lease, int staleAfterSeconds) {
        var claimed = telemetry.claim(target, clock.instant(), lease);
        if (claimed.isEmpty()) return Optional.empty();
        var poll = claimed.orElseThrow();
        var fetch = feed.fetch(target);
        var completedAt = clock.instant();
        // Persistence errors propagate: an uncommitted audit must never be reported as success.
        boolean committed = telemetry.complete(poll, fetch, completedAt, staleAfterSeconds);
        return Optional.of(new Completion(poll, fetch, Duration.between(poll.startedAt(), completedAt), committed));
    }

    public record Completion(ArrivalPoll poll, ArrivalFetch fetch, Duration duration, boolean committed) {}
}
