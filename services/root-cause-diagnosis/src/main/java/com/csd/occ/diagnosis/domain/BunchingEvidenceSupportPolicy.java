package com.csd.occ.diagnosis.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public final class BunchingEvidenceSupportPolicy {
    public static final String VERSION = "bunching-evidence-support-v1";

    public ScoredEvidence score(BunchingEventFacts event, RootCauseContext context, ModelOutput output,
                                Duration lookback, double radiusKm) {
        boolean validScope = "SIMULATION".equals(event.classification())
                && "SIMULATION".equals(context.classification())
                && event.demoRunId() != null && event.demoRunId().equals(context.demoRunId());
        if (!validScope) throw new IllegalArgumentException("Simulation evidence provenance does not match event");
        if (!List.of("TRAFFIC_INCIDENT", "UNKNOWN").contains(output.causeCode()))
            throw new IllegalArgumentException("Unsupported cause code");

        List<String> availableIds = java.util.stream.Stream.of(
                event.evidenceIds().stream(),
                context.arrivals().stream().map(ArrivalEvidence::evidenceId),
                context.incidents().stream().map(IncidentEvidence::evidenceId))
                .flatMap(stream -> stream).distinct().toList();
        if (output.supportingEvidenceIds().stream().anyMatch(id -> !availableIds.contains(id)))
            throw new IllegalArgumentException("Model cited evidence outside the supplied snapshot");

        if ("UNKNOWN".equals(output.causeCode())) {
            return new ScoredEvidence(0, new ConfidenceComponents(validScope ? 1 : 0, 0, 0, 0, 0),
                    event.evidenceIds(), "UNKNOWN", null);
        }
        if (output.supportingEvidenceIds().isEmpty())
            throw new IllegalArgumentException("Traffic incident diagnosis requires supporting evidence");
        List<IncidentEvidence> citedIncidents = context.incidents().stream()
                .filter(incident -> output.supportingEvidenceIds().contains(incident.evidenceId())).toList();
        if (citedIncidents.isEmpty()) throw new IllegalArgumentException("Traffic incident diagnosis must cite an incident");
        if (output.causeSubtype() != null && citedIncidents.stream()
                .noneMatch(incident -> output.causeSubtype().equals(incident.incidentType())))
            throw new IllegalArgumentException("Cause subtype must match cited incident evidence");

        IncidentEvidence best = citedIncidents.stream().max(Comparator.comparingDouble(incident -> {
            double temporal = temporalAlignment(incident, event.firstConfirmedAt(), lookback);
            double spatial = spatialAlignment(incident, context.arrivals(), event.monitoredStopCode(), radiusKm);
            return temporal * 0.30 + spatial * 0.25 + specificity(incident) * 0.20
                    + chronology(incident, event.confirmingObservationTimes()) * 0.25;
        })).orElseThrow();
        double temporal = temporalAlignment(best, event.firstConfirmedAt(), lookback);
        double spatial = spatialAlignment(best, context.arrivals(), event.monitoredStopCode(), radiusKm);
        double specificity = specificity(best);
        double chronology = chronology(best, event.confirmingObservationTimes());
        ConfidenceComponents components = new ConfidenceComponents(1, temporal, spatial, specificity, chronology);
        double score = components.score();
        List<String> ids = output.supportingEvidenceIds().stream().distinct().toList();
        return new ScoredEvidence(score, components, ids, "TRAFFIC_INCIDENT", best.incidentType());
    }

    private double temporalAlignment(IncidentEvidence incident, Instant confirmedAt, Duration lookback) {
        Instant observed = incident.observedAt() == null ? incident.fetchedAt() : incident.observedAt();
        if (observed == null || observed.isAfter(confirmedAt)) return 0;
        double age = Duration.between(observed, confirmedAt).toMillis();
        return Math.max(0, 1 - age / (double) lookback.toMillis());
    }

    private double spatialAlignment(IncidentEvidence incident, List<ArrivalEvidence> arrivals,
                                    String stopCode, double radiusKm) {
        if (incident.latitude() == null || incident.longitude() == null) return 0;
        double distance = arrivals.stream().filter(arrival -> stopCode.equals(arrival.stopCode())
                        && arrival.latitude() != null && arrival.longitude() != null)
                .mapToDouble(arrival -> distanceKm(incident.latitude().doubleValue(), incident.longitude().doubleValue(),
                        arrival.latitude().doubleValue(), arrival.longitude().doubleValue()))
                .min().orElse(Double.POSITIVE_INFINITY);
        return Math.max(0, 1 - distance / radiusKm);
    }

    private double specificity(IncidentEvidence incident) {
        return incident.incidentType() == null || incident.incidentType().isBlank() ? 0
                : "Traffic Incident".equalsIgnoreCase(incident.incidentType()) ? 0.5 : 1;
    }

    private double chronology(IncidentEvidence incident, List<Instant> headwayTimes) {
        Instant observed = incident.observedAt() == null ? incident.fetchedAt() : incident.observedAt();
        long before = headwayTimes.stream().filter(time -> observed != null && !observed.isAfter(time)).count();
        return before == headwayTimes.size() ? 1 : before > 0 ? 0.5 : 0;
    }

    private double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        return 6_371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public record ScoredEvidence(double score, ConfidenceComponents components, List<String> evidenceIds,
                                 String causeCode, String causeSubtype) {
        public ScoredEvidence { evidenceIds = List.copyOf(evidenceIds); }
    }
}
