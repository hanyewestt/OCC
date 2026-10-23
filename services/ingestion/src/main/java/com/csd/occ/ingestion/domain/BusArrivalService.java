package com.csd.occ.ingestion.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BusArrivalService {
    private static final Logger log = LoggerFactory.getLogger(BusArrivalService.class);
    private final PollTargetRepository targets;
    private final PollRunRepository runs;
    private final RawObservationRepository observations;
    private final BusArrivalObservationRepository normalizedObservations;
    private final BusRouteStopRepository routeStops;
    private final FeedStatusRepository feedStatuses;
    private final BusArrivalPort arrivals;
    private final ReferenceTopologyPort topology;
    private final PollingConfigurationPort configuration;
    private final Clock clock;

    public BusArrivalService(PollTargetRepository targets, PollRunRepository runs,
            RawObservationRepository observations, BusArrivalObservationRepository normalizedObservations,
            BusRouteStopRepository routeStops, FeedStatusRepository feedStatuses, BusArrivalPort arrivals, ReferenceTopologyPort topology,
            PollingConfigurationPort configuration, Clock clock) {
        this.targets = targets;
        this.runs = runs;
        this.observations = observations;
        this.normalizedObservations = normalizedObservations;
        this.routeStops = routeStops;
        this.feedStatuses = feedStatuses;
        this.arrivals = arrivals;
        this.topology = topology;
        this.configuration = configuration;
        this.clock = clock;
    }

    public void poll() {
        log.info("Starting Bus Arrival polling loop...");
        List<PollTarget> enabledTargets = targets.findAll().stream()
                .filter(target -> Boolean.TRUE.equals(target.getEnabled()) && target.getServiceId() != null)
                .toList();
        if (enabledTargets.isEmpty()) {
            log.info("No enabled bus-arrival polling targets are configured.");
            return;
        }
        Optional<ReferenceTopologyPort.ReferenceTopologyVersion> activeTopology = topology.activeVersion();
        if (activeTopology.isEmpty()) {
            log.warn("Skipping bus-arrival polling because no active bus reference topology is available.");
            return;
        }
        UUID routePositionPollCycleId = UUID.randomUUID();
        for (PollTarget target : enabledTargets) {
            Instant now = Instant.now(clock);
            String idempotencyKey = target.getSource() + "-" + target.getScopeKey() + "-" + now.getEpochSecond() / 60;
            if (runs.findByIdempotencyKey(idempotencyKey).isPresent()) {
                log.debug("Skipping duplicate polling bucket for {}", target.getScopeKey());
                continue;
            }
            PollRun run = new PollRun();
            run.setId(UUID.randomUUID());
            run.setRoutePositionPollCycleId(routePositionPollCycleId);
            run.setSource(target.getSource());
            run.setScopeKey(target.getScopeKey());
            run.setIdempotencyKey(idempotencyKey);
            run.setStartedAt(now);
            run.setOutcome("RUNNING");
            run.setRecordsReceived(0);
            run.setClassification("OBSERVATION_ONLY");
            runs.save(run);
            try {
                BusArrivalSnapshot response = arrivals.arrivalsAt(target.getStopCode(), target.getServiceId());
                run.setHttpStatus(response.httpStatus());
                if (response.httpStatus() >= 200 && response.httpStatus() < 300 && response.payload() != null) {
                    RawObservation observation = new RawObservation();
                    observation.setId(UUID.randomUUID());
                    observation.setPollRunId(run.getId());
                    observation.setSource(target.getSource());
                    observation.setSourceKey(target.getScopeKey());
                    observation.setRequestParameters("{\"BusStopCode\":\"" + target.getStopCode()
                            + "\",\"ServiceNo\":\"" + target.getServiceId() + "\"}");
                    observation.setPayload(response.payload());
                    observation.setPayloadChecksum(sha256(response.payload()));
                    observation.setFetchedAt(Instant.now(clock));
                    observation.setIngestedAt(Instant.now(clock));
                    observation.setHttpStatus(response.httpStatus());
                    observation.setClassification("OBSERVATION_ONLY");
                    observations.save(observation);
                    List<BusArrivalObservation> normalized = normalize(target, run, observation, response, now,
                            activeTopology.orElseThrow().id());
                    normalizedObservations.saveAll(normalized);
                    run.setOutcome(response.parseErrors().isEmpty() ? "SUCCESS" : "PARTIAL");
                    run.setRecordsReceived(normalized.size());
                    if (!response.parseErrors().isEmpty()) run.setErrorMessage(bounded(String.join("; ", response.parseErrors())));
                    updateFeedStatus(target, now, true, response.parseErrors().isEmpty() ? null : "Some records could not be parsed");
                } else {
                    run.setOutcome("FAILED");
                    run.setErrorCode(String.valueOf(response.httpStatus()));
                    updateFeedStatus(target, now, false, "HTTP " + response.httpStatus());
                }
            } catch (Exception exception) {
                log.error("Failed to poll target {}", target.getScopeKey(), exception);
                run.setOutcome("FAILED");
                run.setErrorMessage(bounded(exception.getMessage()));
                updateFeedStatus(target, now, false, exception.getMessage());
            } finally {
                run.setCompletedAt(Instant.now(clock));
                runs.save(run);
            }
        }
        log.info("Finished Bus Arrival polling loop.");
    }

    private List<BusArrivalObservation> normalize(PollTarget target, PollRun run, RawObservation raw,
            BusArrivalSnapshot snapshot, Instant now, UUID referenceVersionId) {
        List<BusArrivalObservation> result = new ArrayList<>();
        Set<String> monitoredServices = Set.copyOf(configuration.monitoredServices());
        Map<String, List<BusRouteStop>> routes = new LinkedHashMap<>();
        for (BusArrivalPrediction prediction : snapshot.predictions()) {
            if (!monitoredServices.contains(prediction.serviceId())) continue;
            BusArrivalObservation observation = new BusArrivalObservation();
            observation.setId(UUID.randomUUID());
            observation.setRawObservationId(raw.getId());
            observation.setPollRunId(run.getId());
            observation.setReferenceVersionId(referenceVersionId);
            observation.setStopCode(target.getStopCode());
            observation.setServiceId(prediction.serviceId());
            observation.setOperator(blankToNull(prediction.operator()));
            observation.setDirectionId(resolveDirectionId(referenceVersionId, target, prediction, routes));
            observation.setVisitOrder(prediction.visitOrder());
            observation.setVisitNumber(prediction.visitNumber());
            observation.setEstimatedArrivalAt(prediction.estimatedArrivalAt());
            observation.setLatitude(prediction.latitude());
            observation.setLongitude(prediction.longitude());
            observation.setLoadCategory(prediction.loadCategory());
            observation.setFeature(prediction.feature());
            observation.setOriginCode(prediction.originCode());
            observation.setDestinationCode(prediction.destinationCode());
            observation.setMonitored(prediction.monitored());
            observation.setVehicleType(prediction.vehicleType());
            observation.setClassification("OBSERVATION_ONLY");
            // A normalized row is evidence derived from this exact raw snapshot.
            // Keep their timestamps identical so downstream consumers can verify
            // the provenance relationship without relying on clock timing.
            observation.setFetchedAt(raw.getFetchedAt());
            observation.setIngestedAt(raw.getIngestedAt());
            observation.setRecordChecksum(sha256(String.join("|", target.getStopCode(), prediction.serviceId(),
                    String.valueOf(prediction.visitOrder()), String.valueOf(prediction.estimatedArrivalAt()),
                    String.valueOf(prediction.visitNumber()), String.valueOf(prediction.originCode()),
                    String.valueOf(prediction.destinationCode()))));
            result.add(observation);
        }
        return result;
    }

    /**
     * Bus Arrival omits Direction, while BusRoutes supplies a fixed direction for each route stop.
     * Where a stop is served in both directions, terminal codes disambiguate it without guessing.
     */
    private Integer resolveDirectionId(UUID referenceVersionId, PollTarget target, BusArrivalPrediction prediction,
            Map<String, List<BusRouteStop>> routes) {
        if (prediction.directionId() != null) return prediction.directionId();
        String operator = blankToNull(prediction.operator());
        if (operator == null) return null;
        String routeKey = prediction.serviceId() + "|" + operator;
        List<BusRouteStop> route = routes.computeIfAbsent(routeKey, ignored -> routeStops
                .findByIdReferenceVersionIdAndIdServiceIdAndIdOperatorOrderByIdDirectionIdAscIdStopSequenceAsc(
                        referenceVersionId, prediction.serviceId(), operator));
        Map<Short, List<BusRouteStop>> directions = new LinkedHashMap<>();
        for (BusRouteStop routeStop : route) {
            directions.computeIfAbsent(routeStop.id().directionId(), ignored -> new ArrayList<>()).add(routeStop);
        }
        directions.entrySet().removeIf(entry -> entry.getValue().stream()
                .noneMatch(routeStop -> target.getStopCode().equals(routeStop.stopCode())));
        if (directions.size() == 1) return directions.keySet().iterator().next().intValue();
        List<Integer> terminalMatches = directions.entrySet().stream()
                .filter(entry -> matchesTerminals(entry.getValue(), prediction.originCode(), prediction.destinationCode()))
                .map(entry -> entry.getKey().intValue())
                .toList();
        return terminalMatches.size() == 1 ? terminalMatches.getFirst() : null;
    }

    private boolean matchesTerminals(List<BusRouteStop> route, String originCode, String destinationCode) {
        return originCode != null && destinationCode != null
                && originCode.equals(route.getFirst().stopCode())
                && destinationCode.equals(route.getLast().stopCode());
    }

    private void updateFeedStatus(PollTarget target, Instant now, boolean success, String message) {
        FeedStatusId id = new FeedStatusId(target.getSource(), target.getScopeKey());
        FeedStatus status = feedStatuses.findById(id).orElseGet(() -> {
            FeedStatus created = new FeedStatus();
            created.setSource(target.getSource());
            created.setScopeKey(target.getScopeKey());
            created.setConsecutiveFailures(0);
            return created;
        });
        status.setLastAttemptAt(now);
        status.setStaleAfterSeconds(configuration.staleAfterSeconds());
        status.setUpdatedAt(now);
        status.setMessage(bounded(message));
        if (success) {
            status.setStatus("HEALTHY");
            status.setLastSuccessAt(now);
            status.setConsecutiveFailures(0);
        } else {
            int failures = status.getConsecutiveFailures() + 1;
            status.setConsecutiveFailures(failures);
            status.setStatus(failures == 1 ? "DEGRADED" : "FAILED");
        }
        feedStatuses.save(status);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String bounded(String value) {
        if (value == null || value.isBlank()) return null;
        return value.substring(0, Math.min(value.length(), 500));
    }

    private String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }
}
