package com.csd.occ.ingestion.domain;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class ReferenceTopologyQueryService {
    private final ReferenceTopologyPort topology;

    public ReferenceTopologyQueryService(ReferenceTopologyPort topology) {
        this.topology = topology;
    }

    public Page<ReferenceTopologyPort.BusRouteSummary> busRoutes(Pageable pageable) {
        return topology.busRoutes(activeVersion().id(), pageable);
    }

    public Optional<ReferenceTopologyPort.BusRoute> busRoute(String serviceId) {
        return topology.busRoute(activeVersion().id(), serviceId);
    }

    public Page<ReferenceTopologyPort.BusStop> busStops(Pageable pageable) {
        return topology.busStops(activeVersion().id(), pageable);
    }

    public Optional<ReferenceTopologyPort.BusStop> busStop(String stopCode) {
        return topology.busStop(activeVersion().id(), stopCode);
    }

    private ReferenceTopologyPort.ReferenceTopologyVersion activeVersion() {
        return topology.activeVersion().orElseThrow(ReferenceTopologyUnavailableException::new);
    }
}
