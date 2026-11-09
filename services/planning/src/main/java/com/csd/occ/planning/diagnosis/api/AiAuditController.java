package com.csd.occ.planning.diagnosis.api;

import com.csd.occ.planning.diagnosis.api.dto.AiAuditRecordPageResponse;
import com.csd.occ.planning.diagnosis.api.dto.AiAuditRecordResponse;
import com.csd.occ.planning.diagnosis.domain.AiAuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/planning/ai-audit-records")
@Tag(name = "AI audit records")
public class AiAuditController {
    private final AiAuditService audits;

    public AiAuditController(AiAuditService audits) { this.audits = audits; }

    @GetMapping
    @Operation(operationId = "listAiAuditRecords", summary = "List retained AI invocation metadata for the audit screen",
            description = "Controller/admin only. Returns diagnosis and recommendation invocation metadata newest first. Raw model inputs and responses are deliberately excluded.")
    @ApiResponse(responseCode = "200", description = "AI audit record page returned")
    public AiAuditRecordPageResponse list(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        List<AiAuditRecordResponse> items = audits.history(page, size).stream().map(AiAuditRecordResponse::from).toList();
        long totalItems = audits.countHistory();
        return new AiAuditRecordPageResponse(items, page, size, totalItems,
                totalItems == 0 ? 0 : (int) ((totalItems + size - 1) / size));
    }
}
