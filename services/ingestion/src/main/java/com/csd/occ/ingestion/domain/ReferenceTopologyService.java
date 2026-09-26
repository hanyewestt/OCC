package com.csd.occ.ingestion.domain;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReferenceTopologyService implements ReferenceTopologyPort {
    private static final String DATASET = "BUS_TOPOLOGY";
    private static final String ACTIVE = "ACTIVE";
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    private final ReferenceDatasetVersionRepository versions;
    private final BusStopRepository stops;
    private final BusServiceRepository services;
    private final BusRouteStopRepository routeStops;
    private final Clock clock;

    public ReferenceTopologyService(ReferenceDatasetVersionRepository versions, BusStopRepository stops,
                                    BusServiceRepository services, BusRouteStopRepository routeStops, Clock clock) {
        this.versions = versions;
        this.stops = stops;
        this.services = services;
        this.routeStops = routeStops;
        this.clock = clock;
    }

    @Override
    public Optional<ReferenceTopologyVersion> activeVersion() {
        return versions.findByDatasetAndStatus(DATASET, ACTIVE)
                .map(version -> new ReferenceTopologyVersion(version.id(), version.checksum()));
    }

    @Override
    @Transactional
    public ReferenceTopologyVersion replaceIfChanged(BusReferencePort.BusReferenceData data, String checksum) {
        Optional<ReferenceDatasetVersion> active = versions.findByDatasetAndStatus(DATASET, ACTIVE);
        if (active.filter(version -> version.checksum().equals(checksum)).isPresent()) {
            ReferenceDatasetVersion version = active.orElseThrow();
            return new ReferenceTopologyVersion(version.id(), version.checksum());
        }

        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        ReferenceDatasetVersion candidate = versions.save(new ReferenceDatasetVersion(id, DATASET,
                checksum.substring(0, 16), checksum, "CANDIDATE", now));
        stops.saveAll(data.stops().stream().map(stop -> new com.csd.occ.ingestion.domain.BusStop(new BusStopId(id, stop.stopCode()),
                stop.roadName(), stop.description(), GEOMETRY_FACTORY.createPoint(
                new Coordinate(stop.longitude().doubleValue(), stop.latitude().doubleValue())), now)).toList());
        services.saveAll(data.services().stream().map(service -> new BusService(new BusServiceId(id,
                service.serviceId(), service.operator()), service.category(), now)).toList());
        routeStops.saveAll(data.routeStops().stream().map(route -> new com.csd.occ.ingestion.domain.BusRouteStop(new BusRouteStopId(id,
                route.serviceId(), route.operator(), (short) route.directionId(), route.stopSequence()),
                route.stopCode(), route.distanceKm(), now)).toList());

        active.ifPresent(ReferenceDatasetVersion::supersede);
        candidate.activate(now);
        return new ReferenceTopologyVersion(id, checksum);
    }

    @Override
    public List<String> stopsForServices(UUID referenceVersionId, List<String> serviceIds) {
        return routeStops.findByIdReferenceVersionIdAndIdServiceIdIn(referenceVersionId, serviceIds).stream()
                .map(com.csd.occ.ingestion.domain.BusRouteStop::stopCode).distinct().sorted().toList();
    }

    @Override
    public Page<BusRouteSummary> busRoutes(UUID referenceVersionId, Pageable pageable) {
        Page<BusService> page = services.findByIdReferenceVersionId(referenceVersionId, pageable);
        return page.map(service -> new BusRouteSummary(service.id().serviceId(), service.id().operator(),
                service.category()));
    }

    @Override
    public Optional<BusRoute> busRoute(UUID referenceVersionId, String serviceId) {
        List<com.csd.occ.ingestion.domain.BusRouteStop> rows = routeStops
                .findByIdReferenceVersionIdAndIdServiceIdOrderByIdOperatorAscIdDirectionIdAscIdStopSequenceAsc(
                        referenceVersionId, serviceId);
        if (rows.isEmpty()) return Optional.empty();

        Map<String, com.csd.occ.ingestion.domain.BusStop> stopsByCode = new LinkedHashMap<>();
        stops.findAllById(rows.stream().map(row -> new BusStopId(referenceVersionId, row.stopCode())).toList())
                .forEach(stop -> stopsByCode.put(stop.id().stopCode(), stop));
        Map<String, List<com.csd.occ.ingestion.domain.BusRouteStop>> byOperator = new LinkedHashMap<>();
        rows.forEach(row -> byOperator.computeIfAbsent(row.id().operator(), ignored -> new ArrayList<>()).add(row));
        List<BusRouteOperator> operators = byOperator.values().stream()
                .map(operatorRows -> operator(operatorRows, stopsByCode)).toList();
        return Optional.of(new BusRoute(serviceId, operators));
    }

    @Override
    public Page<ReferenceTopologyPort.BusStop> busStops(UUID referenceVersionId, Pageable pageable) {
        Page<com.csd.occ.ingestion.domain.BusStop> page = stops.findByIdReferenceVersionId(referenceVersionId, pageable);
        return new PageImpl<>(page.getContent().stream().map(this::busStop).toList(), pageable, page.getTotalElements());
    }

    @Override
    public Optional<ReferenceTopologyPort.BusStop> busStop(UUID referenceVersionId, String stopCode) {
        return stops.findByIdReferenceVersionIdAndIdStopCode(referenceVersionId, stopCode).map(this::busStop);
    }

    private BusRouteOperator operator(List<com.csd.occ.ingestion.domain.BusRouteStop> operatorRows,
                                      Map<String, com.csd.occ.ingestion.domain.BusStop> stopsByCode) {
        com.csd.occ.ingestion.domain.BusRouteStop first = operatorRows.getFirst();
        Map<Short, List<com.csd.occ.ingestion.domain.BusRouteStop>> byDirection = new LinkedHashMap<>();
        operatorRows.forEach(row -> byDirection.computeIfAbsent(row.id().directionId(), ignored -> new ArrayList<>()).add(row));
        List<BusRouteDirection> directions = byDirection.entrySet().stream().map(entry -> new BusRouteDirection(
                entry.getKey(), entry.getValue().stream().map(row -> routeStop(row, stopsByCode.get(row.stopCode()))).toList()))
                .toList();
        return new BusRouteOperator(first.id().operator(), category(new BusServiceId(first.id().referenceVersionId(),
                first.id().serviceId(), first.id().operator())), directions);
    }

    private String category(BusServiceId id) {
        return services.findById(id).map(BusService::category).orElse(null);
    }

    private ReferenceTopologyPort.BusRouteStop routeStop(com.csd.occ.ingestion.domain.BusRouteStop routeStop,
                                                         com.csd.occ.ingestion.domain.BusStop stop) {
        if (stop == null) throw new IllegalStateException("Route stop is missing its imported stop");
        return new ReferenceTopologyPort.BusRouteStop(stop.id().stopCode(), stop.roadName(), stop.description(),
                BigDecimal.valueOf(stop.location().getY()), BigDecimal.valueOf(stop.location().getX()),
                routeStop.id().stopSequence(), routeStop.distanceKm());
    }

    private ReferenceTopologyPort.BusStop busStop(com.csd.occ.ingestion.domain.BusStop stop) {
        return new ReferenceTopologyPort.BusStop(stop.id().stopCode(), stop.roadName(), stop.description(),
                BigDecimal.valueOf(stop.location().getY()), BigDecimal.valueOf(stop.location().getX()));
    }
}
