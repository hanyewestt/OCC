package com.csd.occ.ingestion.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface TrafficIncidentFeedPort {
    TrafficIncidentBatch trafficIncidents();

    record TrafficIncidentBatch(int httpStatus, String payload, List<TrafficIncident> incidents,
            List<String> errors) {
        public TrafficIncidentBatch {
            incidents = List.copyOf(incidents);
            errors = List.copyOf(errors);
        }
    }

    record TrafficIncident(String sourceIncidentKey, String incidentType, BigDecimal latitude,
            BigDecimal longitude, String message, Instant observedAt) { }
}
