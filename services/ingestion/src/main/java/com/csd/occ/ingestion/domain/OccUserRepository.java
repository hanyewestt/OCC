package com.csd.occ.ingestion.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OccUserRepository extends JpaRepository<OccUser, UUID>, JpaSpecificationExecutor<OccUser> { }
