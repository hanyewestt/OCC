package com.csd.occ.bunching.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceRejectionRepository extends JpaRepository<SourceRejection, UUID> {
}
