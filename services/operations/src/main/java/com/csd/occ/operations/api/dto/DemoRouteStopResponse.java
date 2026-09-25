package com.csd.occ.operations.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

public record DemoRouteStopResponse(String stopCode, int stopSequence,
        @Schema(nullable = true) BigDecimal latitude,
        @Schema(nullable = true) BigDecimal longitude) { }
