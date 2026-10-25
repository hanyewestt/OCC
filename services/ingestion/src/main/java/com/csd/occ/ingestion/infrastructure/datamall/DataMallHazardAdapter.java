package com.csd.occ.ingestion.infrastructure.datamall;

import com.csd.occ.ingestion.domain.HazardFeedPort;
import com.csd.occ.ingestion.domain.HazardKind;
import com.csd.occ.ingestion.domain.HazardLocation;
import com.csd.occ.ingestion.infrastructure.config.HazardFeedProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class DataMallHazardAdapter implements HazardFeedPort {
    private final DataMallAdapter client;
    private final JsonMapper json;
    private final HazardFeedProperties settings;
    public DataMallHazardAdapter(DataMallAdapter client, JsonMapper json, HazardFeedProperties settings) {
        this.client = client; this.json = json; this.settings = settings;
    }

    @Override
    public Batch fetch(HazardKind kind) {
        String endpoint = switch (kind) {
            case FLOOD -> "/PubFloodAlerts";
            case RAIL_DISRUPTION -> "/TrainServiceAlerts";
            case ROADWORKS -> "/RoadWorks";
            case TRAFFIC_INCIDENT -> throw new IllegalArgumentException("Use the existing traffic feed");
        };
        List<String> pages = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        var facts = new LinkedHashMap<String, Fact>();
        int status = 200;
        for (int page = 0; page < settings.maxPages(); page++) {
            try {
                var response = client.get(endpoint + (page == 0 ? "" : "?$skip=" + page * settings.pageSize()));
                status = response.getStatusCode().value();
                String body = response.getBody();
                if (!response.getStatusCode().is2xxSuccessful() || body == null) {
                    errors.add("Hazard page request failed"); break;
                }
                pages.add(body);
                JsonNode root = json.readTree(body);
                JsonNode values = root.isArray() ? root : root.path("value");
                if (kind == HazardKind.RAIL_DISRUPTION && values.isObject()) {
                    parseRail(values, facts, errors); break;
                }
                if (!values.isArray()) { errors.add("Expected hazard record array"); break; }
                for (JsonNode node : values) {
                    try {
                        if (kind == HazardKind.RAIL_DISRUPTION) parseRail(node, facts, errors);
                        else put(facts, kind == HazardKind.FLOOD ? flood(node) : roadworks(node), errors);
                    } catch (RuntimeException invalid) { errors.add("Invalid hazard record"); }
                }
                if (kind == HazardKind.RAIL_DISRUPTION || values.size() < settings.pageSize()) break;
                if (values.size() > settings.pageSize() || page + 1 == settings.maxPages()) {
                    errors.add("Hazard snapshot exceeded pagination bounds"); break;
                }
            } catch (RuntimeException failure) { errors.add("Hazard response could not be fetched or decoded"); break; }
        }
        return new Batch(status, pages, List.copyOf(facts.values()), errors);
    }

    private Fact flood(JsonNode node) {
        String id = required(node, "alertId");
        String action = required(node, "msgType");
        if (!List.of("Alert", "Cancel").contains(action)) throw new IllegalArgumentException("Unknown flood action");
        String circle = text(node, "circle");
        List<HazardLocation> locations = new ArrayList<>();
        if (!circle.isBlank()) {
            String[] parts = circle.split("\\s+");
            String[] coordinates = parts[0].split(",");
            if (parts.length != 2 || coordinates.length != 2) throw new IllegalArgumentException("Invalid broadcast circle");
            locations.add(new HazardLocation("AREA", id, text(node, "areaDesc"), new BigDecimal(coordinates[0]),
                    new BigDecimal(coordinates[1]), new BigDecimal(parts[1]), "BROADCAST_COVERAGE", "PROVIDER_SUPPLIED"));
        } else locations.add(new HazardLocation("AREA", id, text(node, "areaDesc"), null, null, null,
                "LOCATION_DESCRIPTION", "PROVIDER_SUPPLIED"));
        var references = words(text(node, "references"));
        if (action.equals("Cancel") && references.isEmpty()) throw new IllegalArgumentException("Cancellation references required");
        return new Fact(HazardKind.FLOOD, id, action.equals("Cancel") ? "CANCEL" : "OBSERVED",
                instant(node, "dateTime"), null, instant(node, "expires"), text(node, "headline"),
                text(node, "description"), text(node, "severity"), text(node, "instruction"), references,
                null, null, text(node, "status"), null, locations);
    }

    private Fact roadworks(JsonNode node) {
        String road = required(node, "RoadName");
        String id = required(node, "EventID");
        Instant from = LocalDate.parse(required(node, "StartDate")).atStartOfDay(settings.sourceTimezone()).toInstant();
        Instant until = LocalDate.parse(required(node, "EndDate")).plusDays(1).atStartOfDay(settings.sourceTimezone()).toInstant();
        return new Fact(HazardKind.ROADWORKS, id, "OBSERVED", null, from, until, "Approved roadworks", text(node, "Other"),
                null, null, List.of(), null, null, null, text(node, "SvcDept"),
                List.of(new HazardLocation("ROAD", road, road, null, null, null, "ROAD_NAME", "PROVIDER_SUPPLIED")));
    }

    private void parseRail(JsonNode node, LinkedHashMap<String, Fact> facts, List<String> errors) {
        String status = required(node, "Status");
        if (!List.of("1", "2").contains(status)) throw new IllegalArgumentException("Unknown rail status");
        var segments = node.path("AffectedSegments");
        if (!segments.isArray()) throw new IllegalArgumentException("Rail segments missing");
        // Normal status is authoritative for the full rail scope, including empty segments.
        if (status.equals("1")) put(facts, new Fact(HazardKind.RAIL_DISRUPTION, "network", "RECOVERED", null,
                null, null, "Normal train service", "", null, null, List.of(), null, null, status, null, List.of()), errors);
        for (JsonNode segment : segments) {
            String line = required(segment, "Line");
            String direction = text(segment, "Direction");
            List<HazardLocation> locations = words(text(segment, "Stations")).stream().map(code ->
                    new HazardLocation("STATION", code, code, null, null, null, "STATION_REFERENCE", "PROVIDER_SUPPLIED")).toList();
            List<String> messages = new ArrayList<>();
            Instant reported = null;
            for (JsonNode message : node.path("Message")) {
                messages.add(text(message, "Content"));
                String at = text(message, "CreatedDate");
                if (!at.isBlank()) {
                    Instant parsed = LocalDateTime.parse(at, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                            .atZone(settings.sourceTimezone()).toInstant();
                    if (reported == null || parsed.isAfter(reported)) reported = parsed;
                }
            }
            put(facts, new Fact(HazardKind.RAIL_DISRUPTION, line + "|" + direction, status.equals("1") ? "RECOVERED" : "OBSERVED",
                    reported, null, null, "Rail service " + line, String.join(" ", messages), null, null, List.of(),
                    line, direction, status, null, locations), errors);
        }
    }

    private void put(LinkedHashMap<String, Fact> facts, Fact fact, List<String> errors) {
        Fact previous = facts.putIfAbsent(fact.sourceEventKey(), fact);
        if (previous != null && fact.kind() == HazardKind.RAIL_DISRUPTION) {
            // Several disjoint segments may describe one line/direction episode in the same snapshot.
            facts.put(fact.sourceEventKey(), new Fact(fact.kind(), fact.sourceEventKey(), fact.sourceAction(),
                    fact.reportedAt(), fact.effectiveFrom(), fact.effectiveUntil(), fact.title(), fact.description(),
                    fact.severity(), fact.instruction(), fact.references(), fact.line(), fact.direction(), fact.serviceStatus(),
                    fact.department(), java.util.stream.Stream.concat(previous.locations().stream(), fact.locations().stream()).distinct().toList()));
            return;
        }
        if (previous != null && !previous.equals(fact)) errors.add("Conflicting duplicate hazard identity");
    }
    private List<String> words(String value) { return value.isBlank() ? List.of() : List.of(value.split("[,;\\s]+")); }
    private String text(JsonNode node, String field) { return node.path(field).asText("").trim(); }
    private String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value;
    }
    private Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        return value.isBlank() ? null : java.time.OffsetDateTime.parse(value).toInstant();
    }
}
