package com.csd.occ.planning.advisory.api;

import com.csd.occ.planning.advisory.api.dto.InstructionResponse;
import com.csd.occ.planning.advisory.domain.BunchingInstructionService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/planning/recommendations")
public class BunchingInstructionController {
    private final BunchingInstructionService instructions;
    public BunchingInstructionController(BunchingInstructionService instructions) { this.instructions = instructions; }
    @PostMapping("/{id}/obu-decisions")
    @Operation(operationId = "approveBunchingObuInstruction", summary = "Approve and issue a validated HOLD/DWELL instruction atomically",
            description = "Controller/admin only. No body or free-text override. Revalidates existing safety rules, active run, unique recipient and upcoming control stop; repeated issuance returns the original instruction. Movement conflicts return 409.")
    public InstructionResponse approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return InstructionResponse.from(instructions.approveAndIssue(id, jwt.getSubject()));
    }
}
