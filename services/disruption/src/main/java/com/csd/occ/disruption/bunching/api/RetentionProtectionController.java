package com.csd.occ.disruption.bunching.api;
import java.util.List;
import java.util.UUID;
import com.csd.occ.disruption.bunching.domain.RetentionProtectionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
public class RetentionProtectionController {
    private final RetentionProtectionService protection;
    public RetentionProtectionController(RetentionProtectionService protection) { this.protection = protection; }
    @GetMapping("/internal/v1/disruption/retention-protection")
    @io.swagger.v3.oas.annotations.Operation(operationId = "getRetentionProtection", summary = "List simulation runs required by pending work or the selected consumer")
    public ProtectionResponse find() { return new ProtectionResponse(protection.protectedDemoRuns()); }
    public record ProtectionResponse(List<UUID> demoRunIds) { }
}
