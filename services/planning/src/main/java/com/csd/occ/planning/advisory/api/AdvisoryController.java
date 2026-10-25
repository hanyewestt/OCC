package com.csd.occ.planning.advisory.api;

import com.csd.occ.planning.advisory.api.dto.*;
import com.csd.occ.planning.advisory.domain.InstructionService;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/planning") @Tag(name = "Captain advisories")
public class AdvisoryController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdvisoryController.class);
    private final InstructionService instructions;
    private final MeterRegistry metrics;
    public AdvisoryController(InstructionService instructions, MeterRegistry metrics) { this.instructions = instructions; this.metrics = metrics; }
    @GetMapping("/advisories")
    @Operation(operationId = "listAdvisories", summary = "Review structured proposals for the active simulation",
            description = "Controller/admin only. Ordered oldest first. page defaults 0; size defaults 20, range 1–100.")
    public AdvisoryResponse.PageResponse list(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = instructions.proposals(page, size);
        return new AdvisoryResponse.PageResponse(result.map(AdvisoryResponse::from).toList(), page, size, result.getTotalElements(), result.getTotalPages());
    }
    @PostMapping("/advisories/{id}/preview")
    @Operation(operationId = "previewAdvisory", summary = "Validate and preview the exact OBU message", description = "Controller/admin only. Uses current evidence, assignment and the same message policy as issuance; does not issue an instruction.")
    public java.util.Map<String, String> preview(@PathVariable UUID id, @RequestBody @Valid DecideAdvisoryRequest body) {
        return java.util.Map.of("message", instructions.preview(id, body.override()));
    }
    @PostMapping("/advisories/{id}/decisions")
    @Operation(operationId = "decideAdvisory", summary = "Approve or replace a structured advisory and issue it",
            description = "Controller/admin only. Actor comes from JWT. Identical repeated decisions return the original instruction; "
                    + "conflicting decisions return 409. Overrides require a rationale. Explicit replaceInstructionId cancels a conflicting movement instruction atomically.")
    @ApiResponse(responseCode = "200", description = "Issued instruction or identical replay")
    @ApiResponse(responseCode = "400", description = "Invalid structured parameters")
    @ApiResponse(responseCode = "404", description = "Proposal not found")
    @ApiResponse(responseCode = "409", description = "Scope, evidence or movement conflict")
    public InstructionResponse decide(@PathVariable UUID id, @RequestBody @Valid DecideAdvisoryRequest body,
                                      @AuthenticationPrincipal Jwt jwt) {
        var result = instructions.decide(id, body.override(), body.rationale(), jwt.getSubject(), body.replaceInstructionId());
        metrics.counter("occ.planning.instruction.issue", "type", result.type().name()).increment();
        log.info("Instruction issued: instructionId={} controllerSubject={}", result.id(), jwt.getSubject());
        return InstructionResponse.from(result);
    }
    @GetMapping("/instructions")
    @Operation(operationId = "listInstructionHistory", summary = "Read issuance and acknowledgement history", description = "Controller/admin only; active run, newest first.")
    public InstructionResponse.PageResponse history(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var result = instructions.history(page, size);
        return new InstructionResponse.PageResponse(result.map(InstructionResponse::from).toList(), page, size, result.getTotalElements(), result.getTotalPages());
    }
    @PostMapping("/instructions/{id}/cancellations")
    @Operation(operationId = "cancelInstruction", summary = "Cancel an instruction", description = "Controller/admin only; repeated cancellations are safe.")
    public InstructionResponse cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return InstructionResponse.from(instructions.cancel(id, jwt.getSubject()));
    }
    @GetMapping("/captain/instructions")
    @Operation(operationId = "listCaptainInstructions", summary = "Read the signed-in captain's current instructions",
            description = "CAPTAIN only; server resolves the active assignment. page defaults 0; size defaults 20, range 1–100.")
    public InstructionResponse.PageResponse captain(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var found = instructions.captain(jwt.getSubject());
        return new InstructionResponse.PageResponse(found.stream().skip((long) page * size).limit(size).map(InstructionResponse::from).toList(),
                page, size, found.size(), (found.size() + size - 1) / size);
    }
    @PostMapping("/captain/instructions/{id}/acknowledgements")
    @Operation(operationId = "acknowledgeCaptainInstruction", summary = "Acknowledge an assigned instruction",
            description = "CAPTAIN only. No request body. Server records subject/time. A replay returns the original acknowledgement; "
                    + "REDUCE_SPEED duration starts on first acknowledgement. Other captains receive 404; expired/cancelled first acknowledgements return 409.")
    public InstructionResponse acknowledge(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        var result = instructions.acknowledge(id, jwt.getSubject());
        metrics.counter("occ.planning.instruction.acknowledgement", "type", result.type().name()).increment();
        log.info("Instruction acknowledged: instructionId={} captainSubject={}", result.id(), jwt.getSubject());
        return InstructionResponse.from(result);
    }
}
