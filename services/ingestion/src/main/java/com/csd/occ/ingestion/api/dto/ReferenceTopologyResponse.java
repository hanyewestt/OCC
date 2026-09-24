package com.csd.occ.ingestion.api.dto;

import java.util.UUID;

public record ReferenceTopologyResponse(UUID versionId, String checksum) {
}
