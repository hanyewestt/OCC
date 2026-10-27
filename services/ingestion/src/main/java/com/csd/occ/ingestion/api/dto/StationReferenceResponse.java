package com.csd.occ.ingestion.api.dto;

import java.math.BigDecimal;

public record StationReferenceResponse(String code, String name, BigDecimal latitude, BigDecimal longitude,
                                       String versionChecksum) {}
