package com.csd.occ.disruption.traffic.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class TrafficIncidentReadService {
    private final TrafficIncidentStateRepository states;

    public TrafficIncidentReadService(TrafficIncidentStateRepository states) { this.states = states; }

    public Page<TrafficIncidentState> currentOpen(int page, int size) {
        return states.findByStatusOrderByUpdatedAtDesc("ACTIVE", PageRequest.of(page, size));
    }
}
