package com.csd.occ.planning.advisory.api;

import com.csd.occ.planning.advisory.api.dto.InstructionResponse;
import com.csd.occ.planning.advisory.api.dto.ReplaceInstructionRequest;
import com.csd.occ.planning.advisory.domain.BunchingInstructionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/planning/recommendations")
@SecurityRequirement(name = "SupabaseJWT")
public class BunchingInstructionController {
    private final BunchingInstructionService instructions;
    public BunchingInstructionController(BunchingInstructionService instructions) { this.instructions = instructions; }
    @PostMapping("/{id}/obu-decisions")
    @Operation(operationId = "approveBunchingObuInstruction", summary = "Approve and issue a validated HOLD/DWELL instruction atomically",
            description = "Controller/admin only. Optional body may identify replaceInstructionId; no free-text override. Revalidates existing safety rules, active run, unique recipient and upcoming control stop; repeated issuance returns the original instruction. Movement conflicts return 409.")
    public InstructionResponse approve(@PathVariable UUID id, @RequestBody(required = false) ReplaceInstructionRequest body,
                                        @AuthenticationPrincipal Jwt jwt) {
        return InstructionResponse.from(instructions.approveAndIssue(id, jwt.getSubject(), body == null ? null : body.replaceInstructionId()));
    }
}
