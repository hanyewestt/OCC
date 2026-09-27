package com.csd.occ.ingestion.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final ObjectMapper json = new ObjectMapper();

    public DemoReplayService(DemoRunRepository runs, DemoVehicleAssignmentRepository assignments,
            PollRunRepository pollRuns, RawObservationRepository rawObservations,
            BusArrivalObservationRepository arrivals, TrafficIncidentObservationRepository incidents,
            ReferenceTopologyPort topology, Clock clock, DemoModeService mode, DemoReplayConfiguration settings) {
        this.runs = runs; this.assignments = assignments; this.pollRuns = pollRuns; this.rawObservations = rawObservations;
        this.arrivals = arrivals; this.incidents = incidents; this.topology = topology; this.clock = clock;
        this.mode = mode; this.settings = settings;
    }

    @Transactional
    public DemoRun start() {
        if (runs.findActive().isPresent()) throw new IllegalStateException("A demo run is already active");
        if (topology.activeVersion().isEmpty()) throw new IllegalStateException("Demo requires active bus topology");
        DemoRun run = runs.save(new DemoRun(UUID.randomUUID(), clock.instant(), settings.scenario(), settings.seed()));
        String seed = Long.toString(settings.seed());
        assignments.saveAll(List.of(new DemoVehicleAssignment(new DemoVehicleAssignmentId(run.id(), "demo-190-a"),
                        "DEMO-BUS-" + seed + "-01", "DEMO-OPERATOR", "DD", "DEMO-CAPTAIN-" + seed + "-01"),
                new DemoVehicleAssignment(new DemoVehicleAssignmentId(run.id(), "demo-190-b"),
                        "DEMO-BUS-" + seed + "-02", "DEMO-OPERATOR", "DD", "DEMO-CAPTAIN-" + seed + "-02")));
        return run;
    }

    @Transactional
    public void replayNext() {
        if (!mode.enabled()) return;
        runs.findActive().filter(run -> "RUNNING".equals(run.status())).ifPresent(this::emit);
    }

    @Transactional
    public void activate(UUID runId) {
        DemoRun run = runs.findById(runId).orElseThrow(() -> new IllegalStateException("Demo run not found"));
        run.startReplay();
    }

    @Transactional
    public void fail(UUID runId, String reason) {
        runs.findById(runId).ifPresent(run -> run.fail(reason));
    }

    @Transactional
    public void close(UUID runId, Instant now) {
        runs.findById(runId).ifPresent(run -> run.close(now));
    }

    private void emit(DemoRun run) {
        Instant now = clock.instant(); int step = run.currentStep();
        java.time.Duration headway = DemoReplayScenario.headway(step, settings.bunchingThreshold());
        UUID pollId = UUID.randomUUID();
        PollRun poll = new PollRun(); poll.setId(pollId); poll.setSource("DEMO"); poll.setScopeKey("demo:" + run.id());
        poll.setIdempotencyKey("demo:" + run.id() + ":" + step); poll.setStartedAt(now); poll.setCompletedAt(now);
        poll.setOutcome("SUCCESS"); poll.setHttpStatus(200); poll.setRecordsReceived(2); poll.setClassification("SIMULATION"); poll.setDemoRunId(run.id()); pollRuns.save(poll);
        String payload = payload();
        RawObservation raw = new RawObservation(); raw.setId(UUID.randomUUID()); raw.setPollRunId(pollId); raw.setSource("DEMO");
        raw.setSourceKey("demo:" + run.id() + ":stop:44251"); raw.setRequestParameters("{\"BusStopCode\":\"44251\"}");
        raw.setPayload(payload); raw.setPayloadChecksum(hash(payload)); raw.setFetchedAt(now); raw.setIngestedAt(now); raw.setHttpStatus(200);
        raw.setClassification("SIMULATION"); raw.setDemoRunId(run.id()); rawObservations.save(raw);
        UUID reference = topology.activeVersion().orElseThrow().id();
        arrivals.saveAll(List.of(arrival(raw.getId(), pollId, reference, run.id(), "demo-190-a", 1,
                        now.plusSeconds(120), now),
                arrival(raw.getId(), pollId, reference, run.id(), "demo-190-b", 2,
                        now.plusSeconds(120).plus(headway), now)));
        if (step >= 1) incidents.save(new TrafficIncidentObservation(raw.getId(), pollId, run.id(), hash("Vehicle Breakdown|1.3802550|103.7550750|DEMO"), now));
        run.advance(); if (run.currentStep() >= DemoReplayScenario.STEP_COUNT) run.complete(now);
    }

    private BusArrivalObservation arrival(UUID rawId, UUID pollId, UUID reference, UUID runId, String tripId, int visit,
                                           Instant eta, Instant now) {
        BusArrivalObservation value = new BusArrivalObservation(); value.setId(UUID.randomUUID()); value.setRawObservationId(rawId);
        value.setPollRunId(pollId); value.setReferenceVersionId(reference); value.setStopCode("44251"); value.setServiceId("190");
        value.setOperator("SBST"); value.setDirectionId(1); value.setVisitOrder(visit); value.setEstimatedArrivalAt(eta);
        value.setLatitude(new BigDecimal("1.3770000")); value.setLongitude(new BigDecimal("103.7710000")); value.setMonitored(true);
        value.setVehicleType("DD"); value.setFetchedAt(now); value.setIngestedAt(now); value.setRecordChecksum(hash(tripId + eta));
        value.setClassification("SIMULATION"); value.setDemoRunId(runId); value.setTripId(tripId); return value;
    }
    private String payload() { try { return json.writeValueAsString(java.util.Map.of("value", List.of(java.util.Map.of("Type", "Vehicle Breakdown", "Latitude", 1.380255, "Longitude", 103.755075, "Message", "DEMO: Vehicle breakdown")))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
}
