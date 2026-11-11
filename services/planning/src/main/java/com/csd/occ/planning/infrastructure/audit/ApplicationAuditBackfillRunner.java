package com.csd.occ.planning.infrastructure.audit;

import com.csd.occ.planning.domain.audit.ApplicationAuditBackfillService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ApplicationAuditBackfillRunner implements ApplicationRunner {
    private final ApplicationAuditBackfillService backfill;
    public ApplicationAuditBackfillRunner(ApplicationAuditBackfillService backfill) { this.backfill = backfill; }
    @Override public void run(ApplicationArguments args) { backfill.backfillRetainedModelInvocations(); }
}
