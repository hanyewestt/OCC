package com.csd.occ.planning.recommendation.api;

import com.csd.occ.planning.recommendation.api.dto.BusInstructionPageResponse;
import com.csd.occ.planning.recommendation.api.dto.BusInstructionResponse;
import com.csd.occ.planning.recommendation.domain.BusInstructionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/planning/bus-instructions")
@Tag(name = "Bus instructions")
public class BusInstructionController {
    private final BusInstructionService instructions;

    public BusInstructionController(BusInstructionService instructions) {
        this.instructions = instructions;
    }

    @GetMapping
    @Operation(
            operationId = "listMockObuBusInstructions",
            summary = "List instructions for a simulation bus",
            description = "Returns newest-first instructions for the supplied simulation bus reference. "
                    + "ADMIN, CONTROLLER and CAPTAIN may read this mock-OBU endpoint. The query parameter "
                    + "is a demo selector and is not a production device-to-bus authorization mechanism.")
    @ApiResponse(responseCode = "200", description = "Instruction page returned, possibly empty")
    @ApiResponse(responseCode = "400", description = "Bus reference or pagination is invalid",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content)
    @ApiResponse(responseCode = "403", description = "The authenticated role cannot read bus instructions",
            content = @Content)
    public BusInstructionPageResponse instructions(
            @Parameter(description = "Simulation vehicle registration/targetBusReference", example = "SBS5898U")
            @RequestParam @NotBlank @Size(max = 80) String busReference,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        List<BusInstructionResponse> items = instructions.findForBus(busReference, page, size).stream()
                .map(BusInstructionResponse::from)
                .toList();
        long totalItems = instructions.countForBus(busReference);
        int totalPages = totalItems == 0 ? 0 : (int) ((totalItems + size - 1) / size);
        return new BusInstructionPageResponse(items, page, size, totalItems, totalPages);
    }

    @PostMapping("/{instructionId}/acknowledgement")
    @Operation(
            operationId = "acknowledgeMockObuBusInstruction",
            summary = "Acknowledge a simulation bus instruction",
            description = "Records the authenticated CAPTAIN or ADMIN subject and server time once. "
                    + "A replay by the same subject returns the original acknowledgement; a different subject "
                    + "cannot replace it and receives 409.")
    @ApiResponse(responseCode = "200", description = "Instruction acknowledged or the same acknowledgement replayed")
    @ApiResponse(responseCode = "401", description = "Bearer token is missing or invalid", content = @Content)
    @ApiResponse(responseCode = "403", description = "The authenticated role cannot acknowledge instructions",
            content = @Content)
    @ApiResponse(responseCode = "404", description = "Instruction not found",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Instruction was acknowledged by a different subject",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public BusInstructionResponse acknowledge(
            @PathVariable UUID instructionId,
            @AuthenticationPrincipal Jwt jwt) {
        return BusInstructionResponse.from(instructions.acknowledge(instructionId, jwt.getSubject()));
    }
}
