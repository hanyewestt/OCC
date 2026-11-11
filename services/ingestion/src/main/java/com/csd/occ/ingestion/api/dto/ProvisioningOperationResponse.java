package com.csd.occ.ingestion.api.dto;
import com.csd.occ.ingestion.domain.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
public record ProvisioningOperationResponse(UUID id,String actorSubject,String email,String displayName,OccRole role,
        ProvisioningStatus status,Instant createdAt,Instant updatedAt,@Schema(nullable=true) String failureCode,
        @Schema(types={"object","null"},schemaResolution=Schema.SchemaResolution.INLINE) DirectoryUserResponse result) { }
