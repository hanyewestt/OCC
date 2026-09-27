package com.csd.occ.ingestion.domain;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
public interface DemoRunRepository extends JpaRepository<DemoRun, UUID> {
    @Query("select run from DemoRun run where run.status in ('PREPARING', 'RUNNING', 'REPLAY_COMPLETE')")
    Optional<DemoRun> findActive();
}
