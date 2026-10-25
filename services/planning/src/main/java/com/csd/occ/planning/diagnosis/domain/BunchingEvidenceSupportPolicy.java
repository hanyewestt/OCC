package com.csd.occ.planning.diagnosis.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Validates cited evidence and reports associations without assigning confidence or causation. */
public final class BunchingEvidenceSupportPolicy {
    public static final String VERSION = "bunching-evidence-association-v2";

    public AssociatedEvidence associate(BunchingEventFacts event, RootCauseContext context, ModelOutput output,
                                        Duration lookback, double radiusKm) {
        if (!PlanningScope.valid(event.classification(), event.demoRunId())
                || !event.classification().equals(context.classification())
                || !Objects.equals(event.demoRunId(), context.demoRunId()))
            throw new IllegalArgumentException("Evidence provenance does not match event");
        if (lookback == null || lookback.isZero() || lookback.isNegative()
                || !Double.isFinite(radiusKm) || radiusKm <= 0)
            throw new IllegalArgumentException("Positive evidence lookback and radius are required");
        if (!List.of("TRAFFIC_INCIDENT", "UNKNOWN").contains(output.causeCode()))
            throw new IllegalArgumentException("Unsupported cause code");

        List<String> availableIds = java.util.stream.Stream.of(event.evidenceIds().stream(),
                context.arrivals().stream().map(ArrivalEvidence::evidenceId),
                context.incidents().stream().map(IncidentEvidence::evidenceId))
                .flatMap(stream -> stream).distinct().toList();
        if (output.supportingEvidenceIds().stream().anyMatch(id -> !availableIds.contains(id)))
            throw new IllegalArgumentException("Model cited evidence outside the supplied snapshot");
        if ("UNKNOWN".equals(output.causeCode()))
            return new AssociatedEvidence("UNKNOWN", null, event.evidenceIds(), List.of(),
                    List.of("Available evidence does not identify a supported contributing incident."));
        if (output.supportingEvidenceIds().isEmpty())
            throw new IllegalArgumentException("Traffic incident diagnosis requires supporting evidence");

        List<IncidentEvidence> cited = context.incidents().stream()
                .filter(incident -> output.supportingEvidenceIds().contains(incident.evidenceId()))
                .sorted(Comparator.comparing(IncidentEvidence::evidenceId)).toList();
        if (cited.isEmpty()) throw new IllegalArgumentException("Traffic incident diagnosis must cite an incident");
        if (output.causeSubtype() != null && cited.stream()
                .noneMatch(incident -> output.causeSubtype().equals(incident.incidentType())))
            throw new IllegalArgumentException("Cause subtype must match cited incident evidence");
        if (cited.stream().anyMatch(incident -> !event.classification().equals(incident.classification())
                || !Objects.equals(event.demoRunId(), incident.demoRunId())))
            throw new IllegalArgumentException("Cited incident provenance does not match event");

        // Coordinates are bus-position observations, not verified road paths or stop geometry.
        var position = context.arrivals().stream()
                .filter(arrival -> event.monitoredStopCode().equals(arrival.stopCode())
                        && arrival.latitude() != null && arrival.longitude() != null)
                .sorted(Comparator.comparing(ArrivalEvidence::evidenceId)).findFirst();
        List<String> facts = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        List<IncidentEvidence> associated = new ArrayList<>();
        for (IncidentEvidence incident : cited) {
            String label = incident.evidenceId();
            facts.add(label + ": provider-reported incident type " + incident.incidentType() + ".");
            boolean supportedTime = false;
            if (incident.observedAt() == null || event.firstConfirmedAt() == null) {
                limitations.add(label + ": provider report time is unknown; fetch time does not establish incident chronology.");
            } else {
                Duration before = Duration.between(incident.observedAt(), event.firstConfirmedAt());
                supportedTime = !before.isNegative() && before.compareTo(lookback) <= 0;
                facts.add(label + ": provider report time " + incident.observedAt() + ".");
                if (supportedTime)
                    facts.add(label + ": reported " + before.toSeconds() + " seconds before the first confirmed irregularity.");
                else limitations.add(label + ": report time is outside the preceding evidence window.");
            }
            boolean supportedLocation = false;
            if (incident.latitude() == null || incident.longitude() == null || position.isEmpty()) {
                limitations.add(label + ": distance cannot be computed from the available coordinates.");
            } else {
                var arrival = position.orElseThrow();
                double distance = distanceKm(incident.latitude().doubleValue(), incident.longitude().doubleValue(),
                        arrival.latitude().doubleValue(), arrival.longitude().doubleValue());
                supportedLocation = distance <= radiusKm;
                facts.add(String.format(Locale.ROOT, "%s: approximately %.0f m from supplied bus position %s associated with monitored stop %s.",
                        label, distance * 1000, arrival.evidenceId(), event.monitoredStopCode()));
                if (!supportedLocation) limitations.add(label + ": supplied position is outside the evidence radius.");
            }
            boolean knownTimeExcludes = incident.observedAt() != null && event.firstConfirmedAt() != null && !supportedTime;
            if (!knownTimeExcludes && (supportedTime || supportedLocation)
                    && (output.causeSubtype() == null || output.causeSubtype().equals(incident.incidentType())))
                associated.add(incident);
        }
        limitations.add("Road and direction relevance have not been verified; proximity or timing does not establish impact or causation.");
        if (associated.isEmpty()) {
            limitations.add("No cited incident has a supported temporal or spatial association within the evidence bounds.");
            return new AssociatedEvidence("UNKNOWN", null, event.evidenceIds(), facts, limitations);
        }
        List<String> types = associated.stream().map(IncidentEvidence::incidentType).filter(Objects::nonNull).distinct().toList();
        String subtype = output.causeSubtype() != null ? output.causeSubtype() : types.size() == 1 ? types.getFirst() : null;
        List<String> ids = java.util.stream.Stream.concat(event.evidenceIds().stream(),
                associated.stream().map(IncidentEvidence::evidenceId)).distinct().toList();
        return new AssociatedEvidence("TRAFFIC_INCIDENT", subtype, ids, facts, limitations);
    }

    private double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        a = Math.max(0, Math.min(1, a));
        return 6_371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public record AssociatedEvidence(String causeCode, String causeSubtype, List<String> evidenceIds,
                                     List<String> facts, List<String> limitations) {
        public AssociatedEvidence {
            evidenceIds = List.copyOf(evidenceIds); facts = List.copyOf(facts); limitations = List.copyOf(limitations);
        }
    }
}
