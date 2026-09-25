package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(requiredProperties = {"versionId", "checksum"})
public record ReferenceTopologyResponse(UUID versionId, String checksum) {
}
