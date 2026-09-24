package com.csd.occ.ingestion.infrastructure.scheduling;

import com.csd.occ.ingestion.domain.ArrivalPollingService;
import com.csd.occ.ingestion.domain.ArrivalTelemetryPort;
import com.csd.occ.ingestion.infrastructure.config.ArrivalPollingProperties;
import com.csd.occ.ingestion.infrastructure.config.ArrivalSchedulingProperties;
import com.csd.occ.ingestion.domain.ArrivalPollTarget;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "occ.ingestion.arrival.enabled", havingValue = "true", matchIfMissing = true)
public class ArrivalPollingSchedule {
    private static final Logger log = LoggerFactory.getLogger(ArrivalPollingSchedule.class);
    private final ArrivalTelemetryPort telemetry;
    private final ArrivalPollingService service;
    private final ArrivalPollingProperties properties;
    private final ArrivalSchedulingProperties scheduling;
    private final Clock clock;
    private final ThreadPoolExecutor workers;
    private final Set<ArrivalPollTarget> active = ConcurrentHashMap.newKeySet();
    private final HashMap<ArrivalPollTarget, Instant> nextPoll = new HashMap<>();
    private Instant nextDispatch = Instant.MIN;

    public ArrivalPollingSchedule(ArrivalTelemetryPort telemetry, ArrivalPollingService service,
                                 ArrivalPollingProperties properties, ArrivalSchedulingProperties scheduling, Clock clock) {
        this.telemetry = telemetry;
        this.service = service;
        this.properties = properties;
        this.scheduling = scheduling;
        this.clock = clock;
        // No backlog of queued HTTP requests. Saturated targets remain due for the next tick.
        this.workers = new ThreadPoolExecutor(scheduling.maxConcurrentRequests(), scheduling.maxConcurrentRequests(),
                0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
    }

    @Scheduled(fixedDelay = 1000)
    public synchronized void poll() {
        var now = clock.instant();
        if (now.isBefore(nextDispatch)) return;
        nextDispatch = now.plusSeconds(1);
        var targets = telemetry.targets();
        nextPoll.keySet().retainAll(targets);
        var due = targets.stream().filter(target -> !active.contains(target))
                .filter(target -> !now.isBefore(nextPoll.getOrDefault(target, Instant.MIN)))
                .sorted(Comparator.comparing((ArrivalPollTarget target) -> nextPoll.getOrDefault(target, Instant.MIN))
                        .thenComparing(ArrivalPollTarget::scopeKey)).toList();
        int dispatched = 0;
        for (var target : due) {
            if (dispatched == scheduling.maxStartsPerSecond()) break;
            active.add(target);
            try {
                workers.execute(() -> pollTarget(target));
                nextPoll.put(target, now.plusSeconds(60));
                dispatched++;
            } catch (RejectedExecutionException saturated) {
                active.remove(target);
                break;
            }
        }
    }

    private void pollTarget(ArrivalPollTarget target) {
            try {
                service.poll(target, properties.lease(), properties.staleAfterSeconds()).ifPresent(result ->
                        log.info("source={} scope={} pollRunId={} durationMs={} status={} records={} outcome={}",
                                target.source(), target.scopeKey(), result.poll().id(), result.duration().toMillis(),
                                result.fetch().httpStatus().orElse(0), result.fetch().recordsReceived(),
                                !result.committed() ? "LEASE_EXPIRED" : result.fetch().isSuccessful() ? "SUCCESS" : result.fetch().reason()));
            } catch (RuntimeException failure) {
                // SQL/provider exception messages are intentionally excluded from diagnostic logs.
                log.error("source={} scope={} outcome=POLL_PERSISTENCE_FAILED", target.source(), target.scopeKey());
            } finally { active.remove(target); }
    }

    @PreDestroy
    public void close() { workers.shutdownNow(); }
}
