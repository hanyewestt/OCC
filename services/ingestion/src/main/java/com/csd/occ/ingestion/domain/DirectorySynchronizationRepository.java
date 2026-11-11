package com.csd.occ.ingestion.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface DirectorySynchronizationRepository extends JpaRepository<DirectorySynchronization, Short> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select checkpoint from DirectorySynchronization checkpoint where checkpoint.id = 1")
    DirectorySynchronization lockCheckpoint();
}
