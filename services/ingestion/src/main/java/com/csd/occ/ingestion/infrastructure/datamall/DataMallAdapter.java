package com.csd.occ.ingestion.infrastructure.datamall;

import com.csd.occ.ingestion.domain.BusArrivalPort;
import com.csd.occ.ingestion.domain.BusArrivalPrediction;
import com.csd.occ.ingestion.domain.BusArrivalSnapshot;
import com.csd.occ.ingestion.domain.BusReferencePort;
import com.csd.occ.ingestion.domain.TrafficIncidentFeedPort;
import com.csd.occ.ingestion.infrastructure.config.DataMallProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class DataMallAdapter implements BusArrivalPort, BusReferencePort, TrafficIncidentFeedPort {
    private final RestClient restClient;
    private final JsonMapper objectMapper;
    private final DataMallProperties properties;

    public DataMallAdapter(@Qualifier("dataMallRestClient") RestClient restClient,
            DataMallProperties properties, JsonMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public BusArrivalSnapshot arrivalsAt(String stopCode, String serviceId) {
        String path = UriComponentsBuilder.fromPath("/v3/BusArrival")
                .queryParam("BusStopCode", stopCode)
                .queryParam("ServiceNo", serviceId)
                .build()
                .encode()
                .toUriString();
        ResponseEntity<String> response = get(path);
        String payload = response.getBody();
        if (!response.getStatusCode().is2xxSuccessful() || payload == null) {
            return new BusArrivalSnapshot(response.getStatusCode().value(), payload, List.of(), List.of());
        }
        return parseArrivals(payload, response.getStatusCode().value());
    }

    @Override
    public TrafficIncidentBatch trafficIncidents() {
        List<String> rawPages = new ArrayList<>();
        Map<String, TrafficIncident> bySourceKey = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        int pageSize = properties.trafficIncidentPageSize();
        int maxPages = properties.trafficIncidentMaxPages();
        boolean completeSnapshot = false;

        for (int pageNumber = 0; pageNumber < maxPages; pageNumber++) {
            int offset = pageNumber * pageSize;
            String path = pageNumber == 0 ? "/TrafficIncidents" : "/TrafficIncidents?$skip=" + offset;
            ResponseEntity<String> response;
            try {
                response = get(path);
            } catch (RuntimeException failure) {
                if (pageNumber == 0) throw failure;
                errors.add("DataMall traffic incident page request failed");
                break;
            }
            String pagePayload = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || pagePayload == null) {
                if (pageNumber == 0) {
                    return new TrafficIncidentBatch(response.getStatusCode().value(), pagePayload, List.of(),
                            List.of("DataMall traffic incident request failed"));
                }
                errors.add("DataMall traffic incident page " + pageNumber + " returned HTTP "
                        + response.getStatusCode().value());
                break;
            }

            ParsedTrafficIncidents parsed = parseTrafficIncidents(pagePayload);
            if (parsed.validJson()) rawPages.add(pagePayload);
            errors.addAll(parsed.errors());
            for (TrafficIncident incident : parsed.incidents()) {
                TrafficIncident previous = bySourceKey.putIfAbsent(incident.sourceIncidentKey(), incident);
                if (previous != null && !previous.equals(incident)) {
                    errors.add("Traffic incident identity was duplicated with different facts");
                }
            }
            if (!parsed.recordsArray()) break;
            if (parsed.recordsReturned() > pageSize) {
                errors.add("DataMall traffic incident response exceeded the configured page size");
                break;
            }
            if (parsed.recordsReturned() < pageSize) {
                completeSnapshot = true;
                break;
            }
            if (pageNumber + 1 == maxPages) {
                errors.add("DataMall traffic incident feed exceeded the configured maximum page count");
            }
        }

        if (!completeSnapshot && errors.isEmpty()) {
            errors.add("DataMall traffic incident feed did not reach its final page");
        }
        return new TrafficIncidentBatch(200, combineRawPages(rawPages), List.copyOf(bySourceKey.values()),
                List.copyOf(errors));
    }

    @Override
    public BusReferenceData fetch() {
        try {
            List<BusStopRecord> stops = pages("/BusStops").stream()
                    .map(node -> new BusStopRecord(text(node, "BusStopCode"), text(node, "RoadName"),
                            text(node, "Description"), decimal(node, "Latitude"), decimal(node, "Longitude")))
                    .filter(stop -> !stop.stopCode().isBlank() && stop.latitude() != null && stop.longitude() != null)
                    .sorted(Comparator.comparing(BusStopRecord::stopCode))
                    .toList();
            List<BusServiceRecord> services = pages("/BusServices").stream()
                    .map(node -> new BusServiceRecord(text(node, "ServiceNo"), text(node, "Operator"), text(node, "Category")))
                    .filter(service -> !service.serviceId().isBlank() && !service.operator().isBlank())
                    .distinct()
                    .sorted(Comparator.comparing(BusServiceRecord::serviceId).thenComparing(BusServiceRecord::operator))
                    .toList();
            List<BusRouteStopRecord> routeStops = pages("/BusRoutes").stream()
                    .map(node -> new BusRouteStopRecord(text(node, "ServiceNo"), text(node, "Operator"),
                            integer(node, "Direction", 0), integer(node, "StopSequence", 0),
                            text(node, "BusStopCode"), decimal(node, "Distance")))
                    .filter(route -> !route.serviceId().isBlank() && !route.operator().isBlank()
                            && route.directionId() > 0 && route.stopSequence() > 0 && !route.stopCode().isBlank())
                    .sorted(Comparator.comparing(BusRouteStopRecord::serviceId).thenComparing(BusRouteStopRecord::operator)
                            .thenComparingInt(BusRouteStopRecord::directionId).thenComparingInt(BusRouteStopRecord::stopSequence))
                    .toList();
            return new BusReferenceData(stops, services, routeStops);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to fetch DataMall reference topology", exception);
        }
    }

    private BusArrivalSnapshot parseArrivals(String payload, int httpStatus) {
        List<BusArrivalPrediction> predictions = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            for (JsonNode service : objectMapper.readTree(payload).path("Services")) {
                String serviceId = text(service, "ServiceNo");
                String operator = text(service, "Operator");
                for (int position = 1; position <= 3; position++) {
                    String name = position == 1 ? "NextBus" : "NextBus" + position;
                    JsonNode bus = service.path(name);
                    if (bus.isMissingNode() || bus.isEmpty() || text(bus, "EstimatedArrival").isBlank()) continue;
                    try {
                        predictions.add(new BusArrivalPrediction(serviceId, operator, integerOrNull(service, "Direction"), position,
                                optionalText(bus, "OriginCode"), optionalText(bus, "DestinationCode"),
                                Instant.from(OffsetDateTime.parse(text(bus, "EstimatedArrival"))),
                                monitored(bus), decimal(bus, "Latitude"), decimal(bus, "Longitude"),
                                integerOrNull(bus, "VisitNumber"), optionalText(bus, "Load"),
                                optionalText(bus, "Feature"), optionalText(bus, "Type")));
                    } catch (RuntimeException exception) {
                        errors.add(serviceId + " " + name + ": " + bounded(exception.getMessage()));
                    }
                }
            }
        } catch (Exception exception) {
            errors.add("Response JSON: " + bounded(exception.getMessage()));
        }
        return new BusArrivalSnapshot(httpStatus, payload, List.copyOf(predictions), List.copyOf(errors));
    }

    private ParsedTrafficIncidents parseTrafficIncidents(String payload) {
        List<TrafficIncident> incidents = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode records = root.isArray() ? root : root.path("value");
            if (!records.isArray()) {
                errors.add("Traffic incident response was not a JSON array");
                return new ParsedTrafficIncidents(0, true, false, List.of(), List.copyOf(errors));
            }
            for (JsonNode record : records) {
                try {
                    incidents.add(trafficIncident(record));
                } catch (RuntimeException invalidRecord) {
                    errors.add("Traffic incident record could not be normalized");
                }
            }
            return new ParsedTrafficIncidents(records.size(), true, true, List.copyOf(incidents),
                    List.copyOf(errors));
        } catch (Exception invalidResponse) {
            // Do not put malformed provider text into a PostgreSQL jsonb column.
            return new ParsedTrafficIncidents(0, false, false, List.of(),
                    List.of("Traffic incident response was not valid JSON"));
        }
    }

    private String combineRawPages(List<String> rawPages) {
        if (rawPages.isEmpty()) return null;
        if (rawPages.size() == 1) return rawPages.getFirst();
        var envelope = objectMapper.createObjectNode();
        envelope.put("endpoint", "TrafficIncidents");
        var responses = objectMapper.createArrayNode();
        rawPages.forEach(responses::add);
        envelope.set("responses", responses);
        return envelope.toString();
    }

    private TrafficIncident trafficIncident(JsonNode node) {
        String type = text(node, "Type");
        String message = text(node, "Message");
        BigDecimal latitude = decimal(node, "Latitude");
        BigDecimal longitude = decimal(node, "Longitude");
        if (type.isBlank() || type.length() > 60 || message.isBlank() || message.length() > 500
                || latitude == null || longitude == null
                || latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalArgumentException("Traffic incident record is incomplete or outside the stored bounds");
        }
        latitude = latitude.setScale(7, RoundingMode.HALF_UP);
        longitude = longitude.setScale(7, RoundingMode.HALF_UP);
        String sourceKey = incidentKey(type, latitude, longitude);
        return new TrafficIncident(sourceKey, type, latitude, longitude, message, null);
    }

    private String incidentKey(String type, BigDecimal latitude, BigDecimal longitude) {
        String normalizedType = type.toLowerCase(Locale.ROOT);
        String canonical = normalizedType.length() + ":" + normalizedType
                + latitude.toPlainString().length() + ":" + latitude.toPlainString()
                + longitude.toPlainString().length() + ":" + longitude.toPlainString();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private List<JsonNode> pages(String endpoint) {
        List<JsonNode> values = new ArrayList<>();
        for (int skip = 0; ; skip += 500) {
            ResponseEntity<String> response = get(endpoint + "?$skip=" + skip);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException(endpoint + " returned HTTP " + response.getStatusCode().value());
            }
            try {
                JsonNode page = objectMapper.readTree(response.getBody()).path("value");
                if (page.isEmpty()) return values;
                page.forEach(values::add);
            } catch (Exception exception) {
                throw new IllegalStateException("Cannot parse " + endpoint + " page " + skip, exception);
            }
        }
    }

    private String text(JsonNode node, String field) { return node.path(field).asText("").trim(); }
    private String optionalText(JsonNode node, String field) {
        String value = text(node, field);
        return value.isBlank() ? null : value;
    }
    private BigDecimal decimal(JsonNode node, String field) {
        String value = optionalText(node, field);
        return value == null ? null : new BigDecimal(value);
    }
    private int integer(JsonNode node, String field, int defaultValue) {
        Integer value = integerOrNull(node, field);
        return value == null ? defaultValue : value;
    }
    private Integer integerOrNull(JsonNode node, String field) {
        String value = optionalText(node, field);
        return value == null ? null : Integer.valueOf(value);
    }
    private Boolean monitored(JsonNode node) {
        String value = optionalText(node, "Monitored");
        return value == null ? null : "1".equals(value);
    }
    private String bounded(String message) {
        return message == null ? "unknown error" : message.substring(0, Math.min(message.length(), 300));
    }

    ResponseEntity<String> get(String path) {
        return restClient.get().uri(path).header("AccountKey", properties.accountKey())
                .exchange((request, response) -> ResponseEntity.status(response.getStatusCode())
                        .headers(headers -> headers.addAll(response.getHeaders()))
                        .body(readBoundedBody(response)));
    }

    private String readBoundedBody(ClientHttpResponse response) {
        try (var body = response.getBody()) {
            byte[] payload = body.readNBytes(properties.maxResponseBytes() + 1);
            if (payload.length > properties.maxResponseBytes()) {
                throw new IllegalStateException("DataMall response exceeded configured maximum of "
                        + properties.maxResponseBytes() + " bytes");
            }
            return payload.length == 0 ? null : new String(payload, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read DataMall response", failure);
        }
    }

    private record ParsedTrafficIncidents(int recordsReturned, boolean validJson, boolean recordsArray,
            List<TrafficIncident> incidents, List<String> errors) { }
}
