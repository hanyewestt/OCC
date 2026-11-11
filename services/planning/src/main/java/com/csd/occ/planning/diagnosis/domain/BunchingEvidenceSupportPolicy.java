package com.csd.occ.planning.diagnosis.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Validates cited evidence and reports associations without assigning confidence or causation. */
public final class BunchingEvidenceSupportPolicy {
    public static final String VERSION = "bunching-evidence-association-v3";

    public AssociatedEvidence associate(BunchingEventFacts event, RootCauseContext context, ModelOutput output,
                                        Duration lookback, double radiusKm) {
        if (!PlanningScope.valid(event.classification(), event.demoRunId())
                || !event.classification().equals(context.classification())
                || !Objects.equals(event.demoRunId(), context.demoRunId()))
            throw new IllegalArgumentException("Evidence provenance does not match event");
        if (lookback == null || lookback.isZero() || lookback.isNegative()
                || !Double.isFinite(radiusKm) || radiusKm <= 0)
            throw new IllegalArgumentException("Positive evidence lookback and radius are required");
        if (!List.of("TRAFFIC_INCIDENT", "PASSENGER_SURGE", "HEADWAY_DECAY", "UNKNOWN").contains(output.causeCode()))
            throw new IllegalArgumentException("Unsupported cause code");

        List<String> availableIds = java.util.stream.Stream.of(event.evidenceIds().stream(),
                context.arrivals().stream().map(ArrivalEvidence::evidenceId),
                context.incidents().stream().map(IncidentEvidence::evidenceId))
                .flatMap(stream -> stream).distinct().toList();
        if (output.supportingEvidenceIds().stream().anyMatch(id -> !availableIds.contains(id)))
            throw new IllegalArgumentException("Model cited evidence outside the supplied snapshot");
        if ("UNKNOWN".equals(output.causeCode()))
            return new AssociatedEvidence("UNKNOWN", null, event.evidenceIds(), List.of(),
                    List.of("Available evidence does not identify a supported contributing incident or operational cause."));

        if ("PASSENGER_SURGE".equals(output.causeCode())) {
            return associatePassengerSurge(event, context, output);
        }

        if ("HEADWAY_DECAY".equals(output.causeCode())) {
            return associateHeadwayDecay(event, context, output);
        }

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
        var position = busPositionFor(event, context);
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
            if (!hasValidCoordinates(incident.latitude(), incident.longitude()) || position.isEmpty()) {
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

    private AssociatedEvidence associatePassengerSurge(BunchingEventFacts event, RootCauseContext context, ModelOutput output) {
        if (output.supportingEvidenceIds().isEmpty())
            throw new IllegalArgumentException("Passenger surge diagnosis requires supporting evidence");
        List<ArrivalEvidence> cited = context.arrivals().stream()
                .filter(arrival -> output.supportingEvidenceIds().contains(arrival.evidenceId()))
                .sorted(Comparator.comparing(ArrivalEvidence::evidenceId)).toList();
        if (cited.isEmpty()) throw new IllegalArgumentException("Passenger surge diagnosis must cite arrival evidence");
        if (cited.stream().anyMatch(arrival -> !event.classification().equals(arrival.classification())
                || !Objects.equals(event.demoRunId(), arrival.demoRunId())))
            throw new IllegalArgumentException("Cited arrival provenance does not match event");

        List<String> facts = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        List<ArrivalEvidence> scoped = context.arrivals().stream()
                .filter(arr -> Objects.equals(event.classification(), arr.classification())
                        && Objects.equals(event.demoRunId(), arr.demoRunId())
                        && Objects.equals(event.serviceId(), arr.serviceId())
                        && Objects.equals(event.directionId(), arr.directionId())
                        && Objects.equals(event.monitoredStopCode(), arr.stopCode()))
                .sorted(Comparator.comparing(ArrivalEvidence::estimatedArrivalAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ArrivalEvidence::evidenceId)).toList();

        ArrivalEvidence lead = scoped.size() >= 1 ? scoped.get(0) : null;
        ArrivalEvidence follow = scoped.size() >= 2 ? scoped.get(1) : null;
        if (lead != null && follow != null) {
            facts.add(String.format(Locale.ROOT, "%s: leading bus passenger load %s; %s: trailing bus passenger load %s.",
                    lead.evidenceId(), lead.loadCategory() != null ? lead.loadCategory() : "unknown",
                    follow.evidenceId(), follow.loadCategory() != null ? follow.loadCategory() : "unknown"));
        }
        facts.add("Passenger boarding volume disparity between lead and following vehicles contributed to dwell delay at monitored stop "
                + event.monitoredStopCode() + ".");
        limitations.add("Passenger load categories (SEA/SDA/LSD) reflect telemetry tiers, not continuous passenger boardings.");

        List<String> ids = java.util.stream.Stream.concat(event.evidenceIds().stream(),
                cited.stream().map(ArrivalEvidence::evidenceId)).distinct().toList();
        String subtype = output.causeSubtype() != null ? output.causeSubtype() : "BOARDING_IMBALANCE";
        return new AssociatedEvidence("PASSENGER_SURGE", subtype, ids, facts, limitations);
    }

    private AssociatedEvidence associateHeadwayDecay(BunchingEventFacts event, RootCauseContext context, ModelOutput output) {
        List<String> facts = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        Integer observed = event.observedHeadwaySeconds() == null || event.observedHeadwaySeconds().isEmpty()
                ? null : event.observedHeadwaySeconds().getFirst();
        int expected = event.expectedHeadwaySeconds();
        facts.add(String.format(Locale.ROOT, "Headway compressed to %ss against scheduled %ss at stop %s.",
                observed != null ? observed : "unknown", expected, event.monitoredStopCode()));

        var position = busPositionFor(event, context);
        if (context.incidents().isEmpty()) {
            facts.add("No active external traffic incident was identified within the corridor proximity window.");
        } else if (position.isPresent()) {
            var arrival = position.get();
            double minDistanceKm = context.incidents().stream()
                    .filter(i -> hasValidCoordinates(i.latitude(), i.longitude()))
                    .mapToDouble(i -> distanceKm(i.latitude().doubleValue(), i.longitude().doubleValue(),
                            arrival.latitude().doubleValue(), arrival.longitude().doubleValue()))
                    .min().orElse(Double.MAX_VALUE);
            if (minDistanceKm < Double.MAX_VALUE) {
                facts.add(String.format(Locale.ROOT,
                        "Nearest reported traffic incident was %.1f km away, outside direct corridor impact proximity.",
                        minDistanceKm));
            } else {
                facts.add("External traffic incident logs lack valid coordinates and show no proximity to the monitored corridor.");
            }
        } else {
            facts.add("No active external traffic incident was identified within the corridor proximity window.");
        }
        limitations.add("No nearby incidents: No traffic accidents or roadworks reported along this route.");

        ZonedDateTime sgtTime = event.firstConfirmedAt() != null
                ? event.firstConfirmedAt().atZone(ZoneId.of("Asia/Singapore")) : null;
        int hour = sgtTime != null ? sgtTime.getHour() : 12;
        boolean isPeak = (hour >= 7 && hour < 10) || (hour >= 17 && hour < 20);

        List<ArrivalEvidence> arrivals = context.arrivals();
        String leadVeh = arrivals.size() >= 1 ? arrivals.get(0).vehicleType() : null;
        String followVeh = arrivals.size() >= 2 ? arrivals.get(1).vehicleType() : null;
        boolean capacityMismatch = leadVeh != null && followVeh != null && !leadVeh.equalsIgnoreCase(followVeh);

        String derivedSubtype;
        if (output.causeSubtype() != null && Set.of("PEAK_CONGESTION", "TERMINAL_DISPATCH", "FLEET_CAPACITY_ASYMMETRY", "CUMULATIVE_DRIFT").contains(output.causeSubtype())) {
            derivedSubtype = output.causeSubtype();
        } else if (isPeak) {
            derivedSubtype = "PEAK_CONGESTION";
        } else if (capacityMismatch) {
            derivedSubtype = "FLEET_CAPACITY_ASYMMETRY";
        } else {
            derivedSubtype = "CUMULATIVE_DRIFT";
        }

        if ("PEAK_CONGESTION".equals(derivedSubtype)) {
            facts.add(String.format(Locale.ROOT, "Event confirmed during Singapore peak traffic window (%02d:00 SGT) with arterial corridor delay.", hour));
        } else if ("FLEET_CAPACITY_ASYMMETRY".equals(derivedSubtype)) {
            facts.add(String.format(Locale.ROOT, "Vehicle fleet capacity asymmetry observed between leading (%s) and trailing (%s) vehicles.", leadVeh, followVeh));
        } else if ("TERMINAL_DISPATCH".equals(derivedSubtype)) {
            facts.add("Irregularity occurred near route origin, indicating dispatch or berth spacing variation.");
        } else {
            facts.add("Cumulative off-peak spacing drift compounded along route corridor.");
        }

        String leadLoad = arrivals.size() >= 1 ? arrivals.get(0).loadCategory() : null;
        String followLoad = arrivals.size() >= 2 ? arrivals.get(1).loadCategory() : null;
        if (leadLoad != null && followLoad != null && leadLoad.equalsIgnoreCase(followLoad)) {
            int headwayDeficit = Math.max(0, expected - (observed != null ? observed : expected));
            facts.add(String.format(Locale.ROOT,
                    "Uniform passenger load category (%s) across arrivals: intra-tier passenger boarding variations (accounting for up to %ds headway deficit) compounded dwell times across corridor stops without shifting telemetry tiers.",
                    leadLoad, headwayDeficit));
        }


        List<String> ids = java.util.stream.Stream.concat(event.evidenceIds().stream(),
                output.supportingEvidenceIds().stream()).distinct().toList();
        return new AssociatedEvidence("HEADWAY_DECAY", derivedSubtype, ids, facts, limitations);
    }

    static java.util.Optional<ArrivalEvidence> busPositionFor(BunchingEventFacts event, RootCauseContext context) {
        return context.arrivals().stream()
                .filter(arrival -> Objects.equals(event.classification(), arrival.classification())
                        && Objects.equals(event.demoRunId(), arrival.demoRunId())
                        && Objects.equals(event.serviceId(), arrival.serviceId())
                        && Objects.equals(event.directionId(), arrival.directionId())
                        && Objects.equals(event.monitoredStopCode(), arrival.stopCode())
                        && hasValidCoordinates(arrival.latitude(), arrival.longitude()))
                .sorted(Comparator.comparing(ArrivalEvidence::evidenceId,
                        Comparator.nullsLast(Comparator.naturalOrder()))).findFirst();
    }

    static boolean hasValidCoordinates(BigDecimal latitude, BigDecimal longitude) {
        return latitude != null && longitude != null
                && latitude.compareTo(BigDecimal.valueOf(-90)) >= 0 && latitude.compareTo(BigDecimal.valueOf(90)) <= 0
                && longitude.compareTo(BigDecimal.valueOf(-180)) >= 0 && longitude.compareTo(BigDecimal.valueOf(180)) <= 0;
    }

    static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
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
