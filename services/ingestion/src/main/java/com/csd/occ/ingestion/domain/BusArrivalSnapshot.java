package com.csd.occ.ingestion.domain;

import java.util.List;

public record BusArrivalSnapshot(
        int httpStatus,
        String payload,
        List<BusArrivalPrediction> predictions,
        List<String> parseErrors) {
}
