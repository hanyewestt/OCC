package com.csd.occ.ingestion.api.dto;

import java.math.BigDecimal;

public record BusStopResponse(String stopCode, String roadName, String description, BigDecimal latitude,
                              BigDecimal longitude) {
}
