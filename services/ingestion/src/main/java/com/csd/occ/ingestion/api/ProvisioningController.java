package com.csd.occ.ingestion.api;
import com.csd.occ.ingestion.api.dto.*;
import com.csd.occ.ingestion.domain.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping(value="/api/v1/admin",produces="application/json")
@SecurityRequirement(name="bearerAuth") @Tag(name="Account provisioning")
@ApiResponse(responseCode="401",description="Missing or invalid authentication",content=@Content)
@ApiResponse(responseCode="403",description="ADMIN required",content=@Content)
public class ProvisioningController {
    private final ProvisioningService service;
    public ProvisioningController(ProvisioningService service) { this.service=service; }
    @PostMapping(value="/users",consumes="application/json")
    @Operation(operationId="provisionUser",summary="Create a confirmed-email CONTROLLER or CAPTAIN account",
        description="ADMIN only. Idempotency is scoped to actor and key, comparing normalized name/email/role, intentionally excluding password. Completed replay never updates a password. Uncertain creation returns 202; no automatic create retries. PASSWORD_REQUIRED requires a fresh password submission with a NEW key. A late original completion uses the original attempt password.")
    @ApiResponse(responseCode="201",description="Confirmed account, including completed replay",headers=@Header(name="Location",description="Directory account URL",schema=@Schema(type="string",format="uri-reference")),content=@Content(schema=@Schema(implementation=DirectoryUserResponse.class)))
    @ApiResponse(responseCode="202",description="Uncertain or password-required operation; inspect status",headers=@Header(name="Location",description="Operation status URL",schema=@Schema(type="string",format="uri-reference")),content=@Content(schema=@Schema(implementation=ProvisioningOperationResponse.class)))
    @ApiResponse(responseCode="400",description="Invalid fields/key or provider password policy",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
    @ApiResponse(responseCode="409",description="Conflicting replay, pending email reservation or unrelated existing account",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
    public ResponseEntity<?> create(Authentication authentication,@RequestHeader("Idempotency-Key") @Parameter(description="Required actor-scoped key, 1 to 200 characters") String key,@Valid @RequestBody ProvisionUserRequest request) {
        var operation=service.submit(actor(authentication),key,new ProvisioningRequest(request.displayName(),request.email(),request.role(),request.password()));
        if(operation.status()==ProvisioningStatus.SUCCEEDED) return ResponseEntity.created(URI.create("/api/v1/admin/users/"+operation.result().id())).cacheControl(CacheControl.noStore()).body(account(operation.result()));
        return ResponseEntity.accepted().location(URI.create("/api/v1/admin/provisioning-operations/"+operation.id())).cacheControl(CacheControl.noStore()).body(response(operation));
    }
    @GetMapping("/provisioning-operations/{id}")
    @Operation(operationId="readProvisioningOperation",summary="Inspect safe provisioning state for any ADMIN",
        description="Shows original actor and safe result. PASSWORD_REQUIRED is point-in-time absence, not proof a timed-out create cannot finish. Never reuse an old key to submit a new password. If the original operation later succeeds, use the original attempt password.")
    @ApiResponse(responseCode="200",description="Operation state") @ApiResponse(responseCode="404",description="Unknown operation",content=@Content)
    public ResponseEntity<ProvisioningOperationResponse> status(Authentication authentication,@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response(service.status(actor(authentication),id)));
    }
    private DirectoryActor actor(Authentication authentication) {
        var roles=authentication.getAuthorities().stream().map(value -> value.getAuthority()).filter(value -> value.startsWith("ROLE_")).map(value -> OccRole.normalized(value.substring(5))).collect(java.util.stream.Collectors.toSet());
        return new DirectoryActor(authentication.getName(),roles.size()==1 ? roles.iterator().next() : OccRole.UNASSIGNED);
    }
    private ProvisioningOperationResponse response(ProvisioningService.OperationSnapshot value) {
        return new ProvisioningOperationResponse(value.id(),value.actorSubject(),value.email(),value.displayName(),value.role(),value.status(),value.createdAt(),value.updatedAt(),value.failureCode(),value.result()==null ? null : account(value.result()));
    }
    private DirectoryUserResponse account(ProvisioningService.AccountSnapshot value) {
        return new DirectoryUserResponse(value.id(),value.displayName(),value.email(),value.role(),value.available(),value.createdAt(),value.lastSignInAt(),value.synchronizedAt(),null);
    }
}
