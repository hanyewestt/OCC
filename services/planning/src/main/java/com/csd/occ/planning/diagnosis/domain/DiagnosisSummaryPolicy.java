package com.csd.occ.planning.diagnosis.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Builds a concise display summary from the diagnosis's validated incident references and operational telemetry. */
public final class DiagnosisSummaryPolicy {
    private static final String UNKNOWN_SUMMARY =
            "No supported contributing incident or operational cause was identified from the available evidence.";
    private static final int MAX_SUMMARY_LENGTH = 1_000;
    private static final int MAX_SOURCE_DESCRIPTIONS = 2;
    private static final int MAX_SOURCE_DESCRIPTION_LENGTH = 240;
    private static final int MAX_MAIN_CAUSE_TYPES = 8;
    private static final DateTimeFormatter SGT_TIME = DateTimeFormatter.ofPattern("HH:mm 'SGT'", Locale.ROOT)
            .withZone(ZoneId.of("Asia/Singapore"));

    public Summary summarize(String causeCode, String causeSubtype, List<String> evidenceIds, EvidenceSnapshot snapshot) {
        if ("UNKNOWN".equals(causeCode)) return new Summary(UNKNOWN_SUMMARY, List.of());
        if (causeCode == null || (!"TRAFFIC_INCIDENT".equals(causeCode)
                && !"PASSENGER_SURGE".equals(causeCode) && !"HEADWAY_DECAY".equals(causeCode)))
            return new Summary("Diagnosis details are unavailable for this retained result.", List.of());
        if (snapshot == null) return unavailable(causeCode, causeSubtype);
        var event = snapshot.event();
        if (!PlanningScope.valid(event.classification(), event.demoRunId())) return unavailable(causeCode, causeSubtype);

        if ("TRAFFIC_INCIDENT".equals(causeCode)) {
            return summarizeTrafficIncident(causeSubtype, evidenceIds, snapshot);
        }
        if ("PASSENGER_SURGE".equals(causeCode)) {
            return summarizePassengerSurge(causeSubtype, evidenceIds, snapshot);
        }
        if ("HEADWAY_DECAY".equals(causeCode)) {
            return summarizeHeadwayDecay(causeSubtype, evidenceIds, snapshot);
        }
        return new Summary("Diagnosis details are unavailable for this retained result.", List.of());
    }

    private Summary summarizeTrafficIncident(String causeSubtype, List<String> evidenceIds, EvidenceSnapshot snapshot) {
        var event = snapshot.event();
        Set<String> supportedIds = evidenceIds == null ? Set.of() : evidenceIds.stream()
                .filter(Objects::nonNull).filter(id -> !id.isBlank()).collect(Collectors.toSet());
        List<IncidentEvidence> incidents = snapshot.context().incidents().stream()
                .filter(incident -> incident.evidenceId() != null && supportedIds.contains(incident.evidenceId())
                        && snapshot.allowedEvidenceIds().contains(incident.evidenceId())
                        && Objects.equals(event.classification(), incident.classification())
                        && Objects.equals(event.demoRunId(), incident.demoRunId()))
                .sorted(Comparator.comparing(IncidentEvidence::evidenceId))
                .toList();
        if (incidents.isEmpty()) return unavailable("TRAFFIC_INCIDENT", causeSubtype);

        List<String> types = incidents.stream().map(IncidentEvidence::incidentType).map(DiagnosisSummaryPolicy::cleanText)
                .filter(type -> !type.isBlank()).distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder())).toList();
        String typeNames = types.isEmpty() ? cleanText(causeSubtype)
                : String.join(", ", types.stream().limit(MAX_MAIN_CAUSE_TYPES).toList());
        if (types.size() > MAX_MAIN_CAUSE_TYPES)
            typeNames += ", and " + (types.size() - MAX_MAIN_CAUSE_TYPES) + " more";
        if (typeNames.isBlank()) typeNames = "traffic incident";
        String opening = (types.size() > 1 ? "Possible contributors: " : "Possible contributor: ") + typeNames + ". ";

        List<String> allDescriptions = incidents.stream().map(IncidentEvidence::message)
                .map(DiagnosisSummaryPolicy::cleanText).filter(value -> !value.isBlank()).distinct().toList();
        List<String> descriptions = allDescriptions.stream().limit(MAX_SOURCE_DESCRIPTIONS)
                .map(value -> truncate(value, MAX_SOURCE_DESCRIPTION_LENGTH)).toList();
        String description = descriptions.isEmpty() ? "The provider supplied no location description."
                : "The provider reported: " + String.join("; ", descriptions) + ".";
        String additional = allDescriptions.size() > MAX_SOURCE_DESCRIPTIONS
                ? " Additional incident descriptions are available in the evidence details." : "";

        return new Summary(truncate(opening + description + additional, MAX_SUMMARY_LENGTH),
                incidents.stream().map(incident -> detail(incident, snapshot)).toList());
    }

    private Summary summarizePassengerSurge(String causeSubtype, List<String> evidenceIds, EvidenceSnapshot snapshot) {
        var event = snapshot.event();
        var arrivals = snapshot.context().arrivals();
        String leadLoad = null;
        String followLoad = null;
        if (arrivals.size() >= 2) {
            leadLoad = cleanText(arrivals.get(0).loadCategory());
            followLoad = cleanText(arrivals.get(1).loadCategory());
        }
        String leadDesc = formatLoadDescription(leadLoad);
        String followDesc = formatLoadDescription(followLoad);
        String summary = String.format(Locale.ROOT,
                "Passenger boarding surge: High passenger density on leading vehicle (%s) compared to trailing vehicle (%s) caused boarding dwell delay at stop %s.",
                leadDesc, followDesc, cleanText(event.monitoredStopCode()));
        return new Summary(truncate(summary, MAX_SUMMARY_LENGTH), List.of());
    }

    private Summary summarizeHeadwayDecay(String causeSubtype, List<String> evidenceIds, EvidenceSnapshot snapshot) {
        var event = snapshot.event();
        Integer observed = event.observedHeadwaySeconds() == null || event.observedHeadwaySeconds().isEmpty()
                ? null : event.observedHeadwaySeconds().getFirst();
        int expected = event.expectedHeadwaySeconds();
        String stop = cleanText(event.monitoredStopCode());
        String observedStr = observed != null ? observed + "s" : "compressed";

        String summary;
        if ("TERMINAL_DISPATCH".equalsIgnoreCase(causeSubtype)) {
            summary = String.format(Locale.ROOT,
                    "Terminal dispatch irregularity: Headway compressed to %s (scheduled: %ds) near route origin at stop %s, consistent with origin berth delay or dispatch spacing deviation.",
                    observedStr, expected, stop);
        } else if ("FLEET_CAPACITY_ASYMMETRY".equalsIgnoreCase(causeSubtype)) {
            summary = String.format(Locale.ROOT,
                    "Fleet capacity asymmetry: Differing vehicle passenger capacities between leading and trailing vehicles accelerated headway compression to %s (scheduled: %ds) at stop %s.",
                    observedStr, expected, stop);
        } else if ("PEAK_CONGESTION".equalsIgnoreCase(causeSubtype)) {
            String timeStr = formatSingaporeTime(event.firstConfirmedAt());
            summary = String.format(Locale.ROOT,
                    "Peak arterial traffic friction: During rush hour (%s), recurring intersection signal delays and corridor traffic compressed headway from %ds to %s at stop %s.",
                    timeStr, expected, observedStr, stop);
        } else {
            summary = String.format(Locale.ROOT,
                    "Cumulative route drift: Long-distance spacing decay on corridor. In the absence of external road incidents, minor dwell variations compounded to %s (scheduled: %ds) at stop %s.",
                    observedStr, expected, stop);
        }
        return new Summary(truncate(summary, MAX_SUMMARY_LENGTH), List.of());
    }

    private static String formatLoadDescription(String load) {
        if (load == null || load.isBlank()) return "load tier unknown";
        return switch (load.toUpperCase(Locale.ROOT)) {
            case "SEA" -> "Seats Available";
            case "SDA" -> "Standing Available";
            case "LSD" -> "Limited Standing";
            default -> load;
        };
    }

    private static String formatSingaporeTime(Instant time) {
        if (time == null) return "peak period";
        try {
            return SGT_TIME.format(time);
        } catch (Exception e) {
            return "peak period";
        }
    }

    private Summary unavailable(String causeCode, String causeSubtype) {
        String type = cleanText(causeSubtype);
        if (type.isBlank()) {
            if ("PASSENGER_SURGE".equals(causeCode)) type = "passenger boarding surge";
            else if ("HEADWAY_DECAY".equals(causeCode)) type = "headway decay";
            else type = "traffic incident";
        }
        return new Summary(truncate("Possible contributor: " + type
                + ". Details are unavailable for this retained diagnosis.", MAX_SUMMARY_LENGTH), List.of());
    }

    private DiagnosisEvidenceDetail detail(IncidentEvidence incident, EvidenceSnapshot snapshot) {
        var event = snapshot.event();
        var position = BunchingEvidenceSupportPolicy.busPositionFor(event, snapshot.context()).orElse(null);
        Long secondsBefore = incident.observedAt() == null || event.firstConfirmedAt() == null ? null
                : Duration.between(incident.observedAt(), event.firstConfirmedAt()).toSeconds();
        Long distanceMeters = position == null || !BunchingEvidenceSupportPolicy.hasValidCoordinates(
                incident.latitude(), incident.longitude()) ? null
                : Math.round(BunchingEvidenceSupportPolicy.distanceKm(incident.latitude().doubleValue(),
                        incident.longitude().doubleValue(), position.latitude().doubleValue(),
                        position.longitude().doubleValue()) * 1_000);
        boolean positionHasCoordinates = position != null && BunchingEvidenceSupportPolicy.hasValidCoordinates(
                position.latitude(), position.longitude());
        return new DiagnosisEvidenceDetail(incident.evidenceId(), cleanText(incident.incidentType()),
                cleanText(incident.message()), incident.observedAt(), incident.fetchedAt(), event.firstConfirmedAt(),
                secondsBefore, distanceMeters, positionHasCoordinates ? position.evidenceId() : null,
                event.monitoredStopCode());
    }

    private static String cleanText(String value) {
        if (value == null) return "";
        return value.replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>", " ")
                .replaceAll("(?s)<[^>]*>", " ")
                .replaceAll("(?i)\\b(?:traffic-incident|bus-arrival|incident):[^\\s,;]+", " ")
                .replaceAll("\\b[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}\\b", " ")
                .replaceAll("\\b\\d{4}-\\d{2}-\\d{2}T[^\\s]+", " ")
                .replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) return value;
        int end = maxLength;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end).stripTrailing();
    }

    public record Summary(String likelyCause, List<DiagnosisEvidenceDetail> evidenceDetails) {
        public Summary {
            evidenceDetails = List.copyOf(evidenceDetails);
        }
    }
}
