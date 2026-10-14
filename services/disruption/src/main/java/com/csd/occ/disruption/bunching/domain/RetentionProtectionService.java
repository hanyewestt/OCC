package com.csd.occ.disruption.bunching.domain;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class RetentionProtectionService {
    private final AlertEventRepository events;
    public RetentionProtectionService(AlertEventRepository events) { this.events = events; }
    @Transactional(readOnly = true)
    public List<UUID> protectedDemoRuns() { return events.findProtectedDemoRunIds(); }
}
