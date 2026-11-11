package com.csd.occ.ingestion.api;

import com.csd.occ.ingestion.api.dto.*;
import com.csd.occ.ingestion.domain.DirectoryActor;
import com.csd.occ.ingestion.domain.OccRole;
import com.csd.occ.ingestion.domain.UserDirectoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.CacheControl;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1", produces = "application/json")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "User directory")
@ApiResponse(responseCode = "401", description = "Missing or invalid authentication", content = @Content)
@ApiResponse(responseCode = "403", description = "Authenticated role cannot read this directory", content = @Content)
public class UserDirectoryController {
    private final UserDirectoryService directory;

    public UserDirectoryController(UserDirectoryService directory) { this.directory = directory; }

    @GetMapping("/admin/users")
    @Operation(operationId = "listDirectoryUsers", summary = "List provider account snapshots for ADMIN",
            description = "ADMIN only. Includes ADMIN, CONTROLLER, CAPTAIN and UNASSIGNED, including unavailable accounts. "
                    + "Sorted by displayName then UUID; page starts at 0, size defaults to 20 and is limited to 100. "
                    + "The projection never grants permissions; directorySynchronizedAt is null before the first complete scan.")
    @ApiResponse(responseCode = "200", description = "Paged account snapshots")
    @ApiResponse(responseCode = "400", description = "Invalid page, size or role", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<DirectoryPageResponse<DirectoryUserResponse>> users(Authentication authentication,
            @Parameter(description = "Case-insensitive literal substring of name or email") @RequestParam(required = false) String search,
            @RequestParam(required = false) OccRole role,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return response(page(directory.list(actor(authentication), search, role, page, size), this::user));
    }

    @GetMapping("/admin/users/{id}")
    @Operation(operationId = "readDirectoryUser", summary = "Read one provider account snapshot for ADMIN")
    @ApiResponse(responseCode = "200", description = "Account snapshot")
    @ApiResponse(responseCode = "400", description = "Malformed UUID", content = @Content)
    @ApiResponse(responseCode = "404", description = "User has never been projected", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<DirectoryUserResponse> user(@PathVariable UUID id, Authentication authentication) {
        return response(user(directory.detail(actor(authentication), id)));
    }

    @PostMapping("/admin/users/synchronizations")
    @Operation(operationId = "synchronizeDirectoryUsers", summary = "Scan all provider accounts and publish the directory",
            description = "ADMIN only. Performs a complete provider scan before publishing atomically. A failed scan preserves all previous rows and the checkpoint. "
                    + "Identity integration must be explicitly enabled after the external role-hook rollout.")
    @ApiResponse(responseCode = "200", description = "Complete scan published, or a newer complete scan already published")
    @ApiResponse(responseCode = "503", description = "Identity integration disabled or provider unavailable; previous projection preserved", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<DirectorySynchronizationResponse> synchronize(Authentication authentication) {
        return response(new DirectorySynchronizationResponse(directory.synchronize(actor(authentication))));
    }

    @GetMapping("/captains")
    @Operation(operationId = "listDirectoryCaptains", summary = "List all projected captains for ADMIN or CONTROLLER",
            description = "ADMIN or CONTROLLER only. Returns only ID, name, email and the current Ingestion-owned demo assignment. "
                    + "Default browse includes all CAPTAIN accounts, including unavailable accounts. Use available=true for selectors or available=false for unavailable captains. "
                    + "Sorted by displayName then UUID; zero-based page, size 20 by default, maximum 100. "
                    + "Selection does not establish assignment eligibility: mutations must revalidate directly with the provider.")
    @ApiResponse(responseCode = "200", description = "Paged safe captain directory")
    @ApiResponse(responseCode = "400", description = "Invalid page or size", content = @Content)
    public ResponseEntity<DirectoryPageResponse<CaptainResponse>> captains(Authentication authentication,
            @RequestParam(required = false) String search,
            @Parameter(description = "Optional availability filter; omitted includes all captains") @RequestParam(required = false) Boolean available,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return response(page(directory.captains(actor(authentication), search, available, page, size),
                value -> new CaptainResponse(value.id(), value.displayName(), value.email(), assignment(value.currentAssignment()))));
    }

    private DirectoryActor actor(Authentication authentication) {
        Set<OccRole> roles = authentication.getAuthorities().stream().map(authority -> authority.getAuthority())
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> OccRole.normalized(authority.substring(5))).collect(Collectors.toSet());
        return new DirectoryActor(authentication.getName(), roles.size() == 1 ? roles.iterator().next() : OccRole.UNASSIGNED);
    }

    private DirectoryUserResponse user(UserDirectoryService.UserSnapshot value) {
        return new DirectoryUserResponse(value.id(), value.displayName(), value.email(), value.role(), value.available(),
                value.createdAt(), value.lastSignInAt(), value.synchronizedAt(), assignment(value.currentAssignment()));
    }

    private DirectoryAssignmentResponse assignment(UserDirectoryService.CurrentAssignment value) {
        return value == null ? null : new DirectoryAssignmentResponse(value.demoRunId(), value.busId(),
                value.vehicleRegistrationNumber(), value.serviceId(), value.directionId());
    }

    private <T, R> DirectoryPageResponse<R> page(UserDirectoryService.DirectoryPage<T> source, Function<T, R> mapper) {
        return new DirectoryPageResponse<>(source.items().stream().map(mapper).toList(), source.page(), source.size(),
                source.totalItems(), source.totalPages(), source.directorySynchronizedAt());
    }

    private <T> ResponseEntity<T> response(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
