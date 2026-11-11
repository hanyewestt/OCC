package com.csd.occ.ingestion.api.dto;

import com.csd.occ.ingestion.domain.OccRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record DirectoryUserResponse(UUID id, String displayName, String email, OccRole role,
        @Schema(description = "False for missing, banned or deleted provider accounts; never grants permissions") boolean available,
        Instant createdAt, @Schema(nullable = true) Instant lastSignInAt, Instant synchronizedAt,
        @Schema(description = "Assignment owned by Ingestion in a RUNNING or REPLAY_COMPLETE demo run; null when unassigned",
                types = {"object", "null"}, schemaResolution = Schema.SchemaResolution.INLINE)
        DirectoryAssignmentResponse currentAssignment) { }
