package com.csd.occ.ingestion.infrastructure.datamall;

import com.csd.occ.ingestion.domain.BusArrivalPort;
import com.csd.occ.ingestion.domain.BusArrivalPrediction;
import com.csd.occ.ingestion.domain.BusArrivalSnapshot;
import com.csd.occ.ingestion.domain.BusReferencePort;
import com.csd.occ.ingestion.infrastructure.config.DataMallProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class DataMallAdapter implements BusArrivalPort, BusReferencePort {
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DataMallProperties properties;

    public DataMallAdapter(RestTemplateBuilder builder, DataMallProperties properties) {
        this.restTemplate = builder.build();
        this.properties = properties;
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

    private ResponseEntity<String> get(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("AccountKey", properties.accountKey());
        return restTemplate.exchange(properties.baseUrl() + path, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }
}
