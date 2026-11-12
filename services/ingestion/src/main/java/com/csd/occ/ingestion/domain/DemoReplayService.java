package com.csd.occ.ingestion.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DemoReplayService {

    private final DemoRunRepository runs;
    private final DemoVehicleAssignmentRepository assignments;
    private final PollRunRepository pollRuns;
    private final RawObservationRepository rawObservations;
    private final BusArrivalObservationRepository arrivals;
    private final TrafficIncidentObservationRepository incidents;
    private final ReferenceTopologyPort topology;
    private final Clock clock;
    private final DemoModeService mode;
    private final DemoReplayConfiguration settings;
    private final DemoHazardReplayService hazards;
    private final ApplicationAuditEventRepository auditEvents;
    private final IdentityProviderPort identities;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public DemoReplayService(DemoRunRepository runs, DemoVehicleAssignmentRepository assignments,
            PollRunRepository pollRuns, RawObservationRepository rawObservations,
            BusArrivalObservationRepository arrivals, TrafficIncidentObservationRepository incidents,
            ReferenceTopologyPort topology, Clock clock, DemoModeService mode, DemoReplayConfiguration settings,
            DemoHazardReplayService hazards, ApplicationAuditEventRepository auditEvents, IdentityProviderPort identities) {
        this.runs = runs; this.assignments = assignments; this.pollRuns = pollRuns; this.rawObservations = rawObservations;
        this.arrivals = arrivals; this.incidents = incidents; this.topology = topology; this.clock = clock;
        this.mode = mode; this.settings = settings;
        this.hazards = hazards;
        this.auditEvents = auditEvents;
        this.identities = identities;
    }

    /** Compatibility constructor for focused domain tests that do not assign authenticated captains. */
    public DemoReplayService(DemoRunRepository runs, DemoVehicleAssignmentRepository assignments,
            PollRunRepository pollRuns, RawObservationRepository rawObservations,
            BusArrivalObservationRepository arrivals, TrafficIncidentObservationRepository incidents,
            ReferenceTopologyPort topology, Clock clock, DemoModeService mode, DemoReplayConfiguration settings,
            DemoHazardReplayService hazards, ApplicationAuditEventRepository auditEvents) {
        this(runs, assignments, pollRuns, rawObservations, arrivals, incidents, topology, clock, mode, settings,
                hazards, auditEvents, null);
    }

    @Transactional
    public DemoRun start() { return start(DemoRunConfiguration.defaults(), null); }

    @Transactional
    public DemoRun start(DemoRunConfiguration configuration) { return start(configuration, null); }

    @Transactional
    public DemoRun start(DemoRunConfiguration configuration, String actorSubject) {
        if (runs.findActive().isPresent()) throw new IllegalStateException("A demo run is already active");
        if (configuration.scenario() == DemoScenario.NORMAL
                && configuration.busGaps().stream().anyMatch(gap -> gap.compareTo(settings.bunchingThreshold()) <= 0))
            throw new IllegalArgumentException("Normal operation needs a dispatch gap above the bunching threshold");
        validateInitialCaptains(configuration);
        var version = topology.activeVersion().orElseThrow(() -> new IllegalStateException("Demo requires active bus topology"));
        var route = topology.busRoute(version.id(), configuration.serviceId()).orElseThrow(() -> new IllegalArgumentException("Selected bus service is unavailable"));
        if (route.operators().stream().flatMap(operator -> operator.directions().stream())
                .filter(direction -> direction.directionId() == configuration.directionId())
                .noneMatch(direction -> direction.stops().stream().anyMatch(stop -> stop.stopCode().equals(configuration.monitoredStopCode()))))
            throw new IllegalArgumentException("Selected stop is unavailable on this route direction");
        DemoRun run = runs.save(new DemoRun(UUID.randomUUID(), clock.instant(), settings.scenario(), settings.seed(), configuration));
        recordRunEvent(run, "SIMULATION_STARTED", "SUCCESS", "Simulation run started", actorSubject);
        String seed = Long.toString(settings.seed());
        assignments.saveAll(java.util.stream.IntStream.rangeClosed(1, configuration.busCount())
                .mapToObj(number -> assignment(run.id(), configuration, seed, number)).toList());
        return run;
    }

    @Transactional
    public void replayNext() {
        if (!mode.enabled()) return;
        runs.findActiveId().flatMap(runs::findByIdForUpdate)
                .filter(run -> "RUNNING".equals(run.status())).ifPresent(this::emit);
    }

    @Transactional
    public void activate(UUID runId) { activate(runId, null); }

    @Transactional
    public void activate(UUID runId, String actorSubject) {
        DemoRun run = runs.findByIdForUpdate(runId).orElseThrow(() -> new IllegalStateException("Demo run not found"));
        run.startReplay();
        recordRunEvent(run, "SIMULATION_ACTIVATED", "SUCCESS", "Simulation run activated", actorSubject);
    }

    @Transactional
    public void fail(UUID runId, String reason) { fail(runId, reason, null); }

    @Transactional
    public void fail(UUID runId, String reason, String actorSubject) {
        runs.findByIdForUpdate(runId).ifPresent(run -> {
            if ("FAILED".equals(run.status())) return;
            run.fail(reason);
            recordRunEvent(run, "SIMULATION_FAILED", "FAILURE", "Simulation run failed", actorSubject);
        });
    }

    @Transactional
    public void close(UUID runId, Instant now) { close(runId, now, null); }

    @Transactional
    public void close(UUID runId, Instant now, String actorSubject) {
        runs.findByIdForUpdate(runId).ifPresent(run -> {
            if ("CLOSED".equals(run.status())) return;
            run.close(now);
            recordRunEvent(run, "SIMULATION_CLOSED", "SUCCESS", "Simulation run closed", actorSubject);
        });
    }

    private void validateInitialCaptains(DemoRunConfiguration configuration) {
        var ids = configuration.captainUserIds().isEmpty()
                ? settings.captainUserId().stream().toList() : configuration.captainUserIds().stream().filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) return;
        if (identities == null) throw new IdentityProviderException(IdentityProviderException.Reason.UNAVAILABLE);
        for (UUID id : ids) {
            IdentityProviderPort.User user;
            try {
                user = identities.get(id).orElse(null);
            } catch (RuntimeException unavailable) {
                throw new IdentityProviderException(IdentityProviderException.Reason.UNAVAILABLE);
            }
            if (user == null || !user.available() || user.role() != OccRole.CAPTAIN)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "An initial demo captain is not eligible");
        }
    }

    private void recordRunEvent(DemoRun run, String type, String outcome, String summary, String actorSubject) {
        Instant now = clock.instant();
        auditEvents.save(new ApplicationAuditEvent(UUID.randomUUID(), now, now, "OPERATIONS", type, outcome,
                "INGESTION", actorSubject, actorSubject == null ? "SYSTEM" : "CONTROLLER", "DEMO_RUN", run.id().toString(), run.id(), null, null, null,
                summary, java.util.Map.of("status", run.status()), "simulation:" + run.id() + ":" + type));
    }

    private void emit(DemoRun run) {
        Instant now = clock.instant(); int step = run.currentStep();
        emitArrivals(run, step, now, "DEMO", "demo:" + run.id() + ":" + step);
        hazards.emit(run, step, now);
        run.advance(); if (run.currentStep() >= DemoReplayScenario.STEP_COUNT) run.complete(now);
    }

    /** Replays the final arrival pattern under the caller's run lock. */
    void refreshArrivals(DemoRun run, UUID previousObservation, Instant now) {
        emitArrivals(run, DemoReplayScenario.STEP_COUNT - 1, now, "DEMO_ARRIVALS",
                "demo:" + run.id() + ":refresh:" + previousObservation);
    }

    private void emitArrivals(DemoRun run, int step, Instant now, String source, String idempotencyKey) {
        var configuration = run.configuration();
        java.time.Duration defaultHeadway = DemoReplayScenario.headway(step, configuration.dispatchGap(),
                settings.bunchingThreshold(), configuration.scenario());
        List<Duration> headways = step > 0 && configuration.scenario() == DemoScenario.BUNCHING
                && configuration.busGaps().stream().anyMatch(gap -> !gap.equals(configuration.dispatchGap()))
                ? configuration.busGaps()
                : java.util.Collections.nCopies(configuration.busCount() - 1, defaultHeadway);
        UUID pollId = UUID.randomUUID();
        PollRun poll = new PollRun(); poll.setId(pollId); poll.setSource(source); poll.setScopeKey("demo:" + run.id());
        poll.setIdempotencyKey(idempotencyKey); poll.setStartedAt(now); poll.setCompletedAt(now);
        poll.setOutcome("SUCCESS"); poll.setHttpStatus(200); poll.setRecordsReceived(configuration.busCount()); poll.setClassification("SIMULATION"); poll.setDemoRunId(run.id()); pollRuns.save(poll);
        String payload = payload();
        RawObservation raw = new RawObservation(); raw.setId(UUID.randomUUID()); raw.setPollRunId(pollId); raw.setSource("DEMO");
        raw.setSourceKey("demo:" + run.id() + ":stop:" + configuration.monitoredStopCode());
        raw.setRequestParameters("{\"BusStopCode\":\"" + configuration.monitoredStopCode() + "\"}");
        raw.setPayload(payload); raw.setPayloadChecksum(hash(payload)); raw.setFetchedAt(now); raw.setIngestedAt(now); raw.setHttpStatus(200);
        raw.setClassification("SIMULATION"); raw.setDemoRunId(run.id()); rawObservations.save(raw);
        UUID reference = topology.activeVersion().orElseThrow().id();
        Instant firstEta = now.plusSeconds(600L - step * 20L);
        List<BusArrivalObservation> emittedArrivals = new ArrayList<>();
        Instant eta = firstEta;
        for (int number = 1; number <= configuration.busCount(); number++) {
            emittedArrivals.add(arrival(raw.getId(), pollId, reference, run.id(), tripId(configuration.serviceId(), number),
                    configuration.serviceId(), configuration.directionId(), configuration.monitoredStopCode(), number, eta, now));
            if (number < configuration.busCount()) eta = eta.plus(headways.get(number - 1));
        }
        arrivals.saveAll(emittedArrivals);
    }

    private DemoVehicleAssignment assignment(UUID runId, DemoRunConfiguration configuration, String seed, int number) {
        String serviceId = configuration.serviceId();
        String tripId = tripId(serviceId, number);
        UUID captainUserId = configuration.captainUserIds().isEmpty()
                ? number == 1 ? settings.captainUserId().orElse(null) : null
                : configuration.captainUserIds().get(number - 1);
        return new DemoVehicleAssignment(new DemoVehicleAssignmentId(runId, tripId), serviceId, configuration.directionId(),
                "DEMO-" + serviceId + "-" + String.format("%02d", number), "DEMO-OPERATOR", "DD",
                "DEMO-CAPTAIN-" + seed + "-" + String.format("%02d", number),
                captainUserId);
    }

    private BusArrivalObservation arrival(UUID rawId, UUID pollId, UUID reference, UUID runId,
                                           String tripId, String serviceId, int directionId, String stopCode, int visit,
                                           Instant eta, Instant now) {
        BusArrivalObservation value = new BusArrivalObservation(); value.setId(UUID.randomUUID()); value.setRawObservationId(rawId);
        value.setPollRunId(pollId); value.setReferenceVersionId(reference); value.setStopCode(stopCode); value.setServiceId(serviceId);
        value.setOperator("DEMO-OPERATOR"); value.setDirectionId(directionId); value.setVisitOrder(visit); value.setEstimatedArrivalAt(eta);
        value.setLatitude(new BigDecimal("1.3770000")); value.setLongitude(new BigDecimal("103.7710000")); value.setMonitored(true);
        value.setVehicleType("DD"); value.setFetchedAt(now); value.setIngestedAt(now); value.setRecordChecksum(hash(tripId + eta));
        value.setClassification("SIMULATION"); value.setDemoRunId(runId); value.setTripId(tripId);
        value.setRouteStopSequence(routeStopSequence(reference, serviceId, directionId, stopCode));
        return value;
    }

    private String tripId(String serviceId, int number) { return "demo-" + serviceId + "-" + number; }

    private Integer routeStopSequence(UUID referenceVersionId, String serviceId, int directionId, String stopCode) {
        return topology.busRoute(referenceVersionId, serviceId).stream()
                .flatMap(route -> route.operators().stream())
                .flatMap(operator -> operator.directions().stream())
                .filter(direction -> direction.directionId() == directionId)
                .flatMap(direction -> direction.stops().stream())
                .filter(stop -> stopCode.equals(stop.stopCode()))
                .mapToInt(ReferenceTopologyPort.BusRouteStop::stopSequence)
                .min().stream().boxed().findFirst().orElse(null);
    }
    private String payload() { try { return json.writeValueAsString(java.util.Map.of("value", List.of(java.util.Map.of("Type", "Vehicle Breakdown", "Latitude", 1.380255, "Longitude", 103.755075, "Message", "DEMO: Vehicle breakdown")))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
}
