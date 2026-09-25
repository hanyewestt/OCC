package com.csd.occ.operations.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DemoDriverRepository extends JpaRepository<DemoDriver, UUID> { }
