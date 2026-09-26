package com.csd.occ.ingestion.domain;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DemoRunService {
    private final DemoReplayService replay;
    private final DemoRunRepository runs;
    private final DemoModeService mode;
    private final BunchingControlPort bunching;
    private final BusArrivalObservationRepository arrivals;
    private final ReferenceTopologyPort topology;
    private final Clock clock;

    public DemoRunService(DemoReplayService replay, DemoRunRepository runs, DemoModeService mode,
                          BunchingControlPort bunching, BusArrivalObservationRepository arrivals,
                          ReferenceTopologyPort topology, Clock clock) {
        this.replay = replay; this.runs = runs; this.mode = mode; this.bunching = bunching;
        this.arrivals = arrivals; this.topology = topology; this.clock = clock;
    }

    public DemoRunDetails start() {
        Optional<DemoRun> current = runs.findActive();
        if (current.isPresent()) throw new DemoRunAlreadyActiveException(current.get().id());

        DemoRun run;
        try {
            run = replay.start();
        } catch (RuntimeException failure) {
            current = runs.findActive();
            if (current.isPresent()) throw new DemoRunAlreadyActiveException(current.get().id());
            throw failure;
        }

        try {
            bunching.selectDemo(run.id());
            mode.setEnabled(true);
            replay.activate(run.id());
        } catch (RuntimeException failure) {
            try { bunching.selectLive(); } catch (RuntimeException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            try { replay.fail(run.id(), "Bunching control unavailable"); }
            catch (RuntimeException persistenceFailure) { failure.addSuppressed(persistenceFailure); }
            try { mode.setEnabled(false); }
            catch (RuntimeException persistenceFailure) { failure.addSuppressed(persistenceFailure); }
            throw new DemoBunchingUnavailableException();
        }
        return details(run);
    }

    public Optional<DemoRunDetails> active() {
        return runs.findActive().map(this::details);
    }

    public DemoRunDetails status(UUID id) {
        DemoRun run = runs.findById(id).orElseThrow(() -> new DemoRunNotFoundException(id));
        return details(run);
    }

    public DemoRunDetails close(UUID id) {
        DemoRun run = runs.findById(id).orElseThrow(() -> new DemoRunNotFoundException(id));
        if (!"CLOSED".equals(run.status())) {
            Optional<DemoRun> current = runs.findActive();
            if (current.isPresent() && !current.get().id().equals(id))
                throw new DemoRunAlreadyActiveException(current.get().id());
            returnToLive(id);
        }
        return new DemoRunDetails(run.id(), "CLOSED", run.currentStep(), List.of());
    }

    public boolean setMode(boolean enabled) {
        if (enabled) {
            Optional<DemoRun> current = runs.findActive();
            if (current.isPresent()) {
                try { bunching.selectDemo(current.get().id()); }
                catch (RuntimeException failure) { throw new DemoBunchingUnavailableException(); }
                mode.setEnabled(true);
            } else {
                // Enabling demo mode is an action, not just a flag: create and select a
                // simulation run so the consumer cannot remain idle on the live feed.
                start();
            }
        } else {
            Optional<DemoRun> current = runs.findActive();
            try {
                bunching.selectLive();
            } catch (RuntimeException failure) {
                throw new DemoBunchingUnavailableException();
            }
            current.ifPresent(run -> replay.close(run.id(), clock.instant()));
            mode.setEnabled(false);
        }
        return mode.enabled();
    }

    private void returnToLive(UUID runId) {
        try {
            bunching.selectLive();
        } catch (RuntimeException failure) {
            throw new DemoBunchingUnavailableException();
        }
        mode.setEnabled(false);
        replay.close(runId, clock.instant());
    }

    private DemoRunDetails details(DemoRun run) {
        List<DemoAlertDetails> result = new ArrayList<>();
        for (DemoBunchingAlert alert : bunching.alerts(run.id())) {
            if (!"SIMULATION".equals(alert.classification()) || alert.leadingObservationId() == null
                    || alert.followingObservationId() == null) continue;
            DemoSimulationArrivalDetails leading = arrival(run.id(), alert.leadingObservationId()).orElse(null);
            DemoSimulationArrivalDetails following = arrival(run.id(), alert.followingObservationId()).orElse(null);
            StopDisplay stop = stop(alert.stopCode());
            result.add(new DemoAlertDetails(alert.action(), alert.classification(), alert.serviceId(), alert.stopCode(),
                    stop.name(), stop.roadName(), alert.directionId(), alert.detectedAt(), headwaySeconds(leading, following),
                    leading == null ? null : leading.vehicle(), following == null ? null : following.vehicle()));
        }
        return new DemoRunDetails(run.id(), run.status(), run.currentStep(), result);
    }

    private Optional<DemoSimulationArrivalDetails> arrival(UUID runId, UUID observationId) {
        return arrivals.findSimulationArrivalDetails(runId, observationId);
    }

    private StopDisplay stop(String stopCode) {
        return topology.activeVersion().flatMap(version -> topology.busStop(version.id(), stopCode))
                .map(value -> new StopDisplay(value.description(), value.roadName()))
                .orElseGet(() -> new StopDisplay(null, null));
    }

    private Long headwaySeconds(DemoSimulationArrivalDetails leading, DemoSimulationArrivalDetails following) {
        if (leading == null || following == null || leading.estimatedArrivalAt() == null
                || following.estimatedArrivalAt() == null) return null;
        return Math.abs(Duration.between(leading.estimatedArrivalAt(), following.estimatedArrivalAt()).getSeconds());
    }

    private record StopDisplay(String name, String roadName) {}
}
