package com.csd.occ.disruption.traffic.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrafficIncidentEventRepository extends JpaRepository<TrafficIncidentEvent, UUID> { }
