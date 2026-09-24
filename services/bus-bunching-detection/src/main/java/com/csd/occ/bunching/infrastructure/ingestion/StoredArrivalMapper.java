package com.csd.occ.bunching.infrastructure.ingestion;

import com.csd.occ.bunching.domain.ArrivalSnapshot;
import com.csd.occ.bunching.domain.InvalidArrivalException;
import com.csd.occ.bunching.domain.PendingArrivalException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;

/** Maps stored provider content; does not call DataMall or write ingestion rows. */
@Component
public class StoredArrivalMapper {
    private final JsonMapper json;

    public StoredArrivalMapper(JsonMapper json) { this.json = json; }

    public ArrivalSnapshot map(StoredSnapshot stored, List<RouteDirection> routes) {
        try {
            var payload = json.readTree(stored.payload());
            String stop = requiredText(payload, "BusStopCode");
            if (!stored.sourceKey().equals("stop:" + stop)) throw invalid("STOP_MISMATCH");
            var services = payload.path("Services");
            if (!services.isArray()) throw invalid("INVALID_SERVICES");
            var lastSlots = validatedLastSlots(services);
            var normalized = json.readTree(stored.normalizedArrivals());
            if (!normalized.isArray()) throw invalid("INVALID_NORMALIZED_ROWS");
            return normalized.isEmpty() ? fromRaw(stored, stop, services, routes, lastSlots)
                    : fromNormalized(stored, stop, services, normalized, lastSlots);
        } catch (InvalidArrivalException | PendingArrivalException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid("MALFORMED_STORED_ARRIVAL");
        }
    }

    private ArrivalSnapshot fromRaw(StoredSnapshot stored, String stop, JsonNode services,
                                     List<RouteDirection> routes, Map<String, Integer> lastSlots) {
        var arrivals = new ArrayList<ArrivalSnapshot.Arrival>();
        var references = new HashSet<UUID>();
        for (var service : services) {
            String serviceId = requiredText(service, "ServiceNo");
            String operator = requiredText(service, "Operator");
            int last = lastSlots.get(serviceId);
            for (int slot = 1; slot <= Math.max(1, last); slot++) {
                var bus = bus(service, slot);
                String estimate = text(bus, "EstimatedArrival");
                String origin = text(bus, "OriginCode");
                String destination = text(bus, "DestinationCode");
                // Repeated-stop visits need richer topology than this initial detector supports.
                var matches = routes.stream().filter(route -> route.serviceId().equals(serviceId)
                        && route.operator().equals(operator) && route.stopCode().equals(stop)
                        && route.originCode().equals(origin) && route.destinationCode().equals(destination)
                        && route.stopVisits() == 1 && text(bus, "VisitNumber").equals("1")).toList();
                Optional<Integer> direction = Optional.empty();
                if (matches.size() == 1) {
                    direction = Optional.of(matches.getFirst().directionId());
                    references.add(matches.getFirst().referenceVersionId());
                }
                // Deterministic evidence ID for a raw slot, never a fabricated vehicle identifier.
                UUID id = UUID.nameUUIDFromBytes((stored.id() + ":" + serviceId + ":" + slot)
                        .getBytes(StandardCharsets.UTF_8));
                arrivals.add(new ArrivalSnapshot.Arrival(id, serviceId, stop, direction, slot,
                        estimate.isBlank() ? Optional.empty() : Optional.of(Instant.parse(estimate))));
            }
        }
        if (references.size() > 1) throw invalid("MIXED_REFERENCE_VERSIONS");
        return new ArrivalSnapshot(stored.id(), stored.pollRunId(), references.stream().findFirst(),
                stored.fetchedAt(), arrivals);
    }

    private ArrivalSnapshot fromNormalized(StoredSnapshot stored, String stop, JsonNode services, JsonNode rows,
                                           Map<String, Integer> lastSlots) {
        var arrivals = new ArrayList<ArrivalSnapshot.Arrival>();
        var references = new HashSet<UUID>();
        var availableSlots = new HashSet<String>();
        for (var row : rows) {
            if (!UUID.fromString(requiredText(row, "raw_observation_id")).equals(stored.id())
                    || !UUID.fromString(requiredText(row, "poll_run_id")).equals(stored.pollRunId())
                    || !requiredText(row, "stop_code").equals(stop)
                    || !Instant.parse(requiredText(row, "fetched_at")).equals(stored.fetchedAt())) {
                throw invalid("NORMALIZED_PROVENANCE_MISMATCH");
            }
            references.add(UUID.fromString(requiredText(row, "reference_version_id")));
            String serviceId = requiredText(row, "service_id");
            int slot = row.path("visit_order").asInt();
            availableSlots.add(serviceId + ":" + slot);
            var serviceMatches = new ArrayList<JsonNode>();
            for (var service : services) {
                if (text(service, "ServiceNo").equals(serviceId)) serviceMatches.add(service);
            }
            if (serviceMatches.size() != 1) throw invalid("NORMALIZED_SERVICE_MISMATCH");
            var rawBus = bus(serviceMatches.getFirst(), slot);
            Optional<Integer> direction = row.path("direction_id").isIntegralNumber()
                    && text(rawBus, "VisitNumber").equals("1")
                    ? Optional.of(row.path("direction_id").asInt()) : Optional.empty();
            String estimate = text(row, "estimated_arrival_at");
            arrivals.add(new ArrivalSnapshot.Arrival(UUID.fromString(requiredText(row, "id")), serviceId, stop,
                    direction, slot, estimate.isBlank() ? Optional.empty() : Optional.of(Instant.parse(estimate))));
        }
        // Never interpret a partially committed normalization batch as a complete snapshot.
        for (var service : lastSlots.entrySet()) {
            for (int slot = 1; slot <= service.getValue(); slot++) {
                if (!availableSlots.contains(service.getKey() + ":" + slot)) {
                    throw new PendingArrivalException();
                }
            }
        }
        if (references.size() != 1) throw invalid("MIXED_REFERENCE_VERSIONS");
        return new ArrivalSnapshot(stored.id(), stored.pollRunId(), references.stream().findFirst(),
                stored.fetchedAt(), arrivals);
    }

    /** Shared by both mappings: populated slots must form a contiguous prefix starting at 1. */
    private Map<String, Integer> validatedLastSlots(JsonNode services) {
        var lastSlots = new LinkedHashMap<String, Integer>();
        for (var service : services) {
            String serviceId = requiredText(service, "ServiceNo");
            if (lastSlots.containsKey(serviceId)) throw invalid("DUPLICATE_SERVICE");
            int last = 0;
            boolean missing = false;
            for (int slot = 1; slot <= 3; slot++) {
                if (text(bus(service, slot), "EstimatedArrival").isBlank()) {
                    missing = true;
                } else {
                    if (missing) throw invalid("INCOMPLETE_ARRIVAL_SEQUENCE");
                    last = slot;
                }
            }
            lastSlots.put(serviceId, last);
        }
        return lastSlots;
    }

    private JsonNode bus(JsonNode service, int slot) {
        return service.path(slot == 1 ? "NextBus" : "NextBus" + slot);
    }

    private String requiredText(JsonNode node, String name) {
        String value = text(node, name);
        if (value.isBlank()) throw invalid("MISSING_REQUIRED_FIELD");
        return value;
    }

    private String text(JsonNode node, String name) {
        var field = node.path(name);
        if (field.isMissingNode() || field.isNull()) return "";
        if (!field.isString()) throw invalid("INVALID_FIELD_TYPE");
        return field.asString();
    }

    private InvalidArrivalException invalid(String reason) { return new InvalidArrivalException(reason); }

    public record StoredSnapshot(UUID id, UUID pollRunId, Instant fetchedAt, String sourceKey,
                                 String payload, String normalizedArrivals) {}

    public record RouteDirection(UUID referenceVersionId, String serviceId, String operator, int directionId,
                                 String originCode, String destinationCode, String stopCode, int stopVisits) {}
}
