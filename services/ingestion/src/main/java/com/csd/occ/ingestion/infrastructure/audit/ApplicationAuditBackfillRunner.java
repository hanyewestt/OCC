package com.csd.occ.ingestion.infrastructure.audit;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class ApplicationAuditBackfillRunner implements ApplicationRunner {
    private final ApplicationAuditBackfillService backfill;

    public ApplicationAuditBackfillRunner(ApplicationAuditBackfillService backfill) {
        this.backfill = backfill;
    }

    @Override
    public void run(ApplicationArguments args) {
        backfill.backfillRetainedRecords();
    }
}
