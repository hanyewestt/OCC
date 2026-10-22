package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Predicted gap between this bus and one neighbouring bus")
public record PacingGapResponse(
        @Schema(description = "Predicted time between the two buses reaching the stop, in whole seconds",
                example = "45", requiredMode = Schema.RequiredMode.REQUIRED)
        long headwaySeconds,
        @Schema(description = "TOO_CLOSE at or below target x too-close ratio (bunching); TOO_FAR at or above "
                + "target x too-far ratio (service gap); otherwise ON_TARGET",
                allowableValues = {"TOO_CLOSE", "ON_TARGET", "TOO_FAR"}, example = "TOO_CLOSE",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String state) {}
