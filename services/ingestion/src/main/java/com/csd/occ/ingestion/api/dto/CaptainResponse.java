package com.csd.occ.ingestion.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record CaptainResponse(UUID id, String displayName, String email,
        // Explicit OAS 3.1 types retain the object fields and avoid a contradictory nullable $ref.
        @Schema(types = {"object", "null"}, schemaResolution = Schema.SchemaResolution.INLINE)
        DirectoryAssignmentResponse currentAssignment) { }
