package com.csd.occ.ingestion.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ReferenceImportService {
    private final BusReferencePort source;
    private final ReferenceTopologyPort topology;

    public ReferenceImportService(BusReferencePort source, ReferenceTopologyPort topology) {
        this.source = source;
        this.topology = topology;
    }

    public ReferenceTopologyPort.ReferenceTopologyVersion refresh() {
        BusReferencePort.BusReferenceData sourceData = source.fetch();
        BusReferencePort.BusReferenceData data = completeServices(sourceData);
        return topology.replaceIfChanged(data, checksum(data));
    }

    private BusReferencePort.BusReferenceData completeServices(BusReferencePort.BusReferenceData data) {
        List<BusReferencePort.BusServiceRecord> services = new ArrayList<>(data.services());
        Set<String> known = new HashSet<>();
        services.forEach(service -> known.add(service.serviceId() + "|" + service.operator()));
        data.routeStops().forEach(route -> {
            String key = route.serviceId() + "|" + route.operator();
            if (known.add(key)) services.add(new BusReferencePort.BusServiceRecord(route.serviceId(), route.operator(), null));
        });
        services.sort(Comparator.comparing(BusReferencePort.BusServiceRecord::serviceId)
                .thenComparing(BusReferencePort.BusServiceRecord::operator));
        return new BusReferencePort.BusReferenceData(data.stops(), List.copyOf(services), data.routeStops());
    }

    private String checksum(BusReferencePort.BusReferenceData data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            data.stops().forEach(stop -> update(digest, stop.stopCode(), stop.roadName(), stop.description(),
                    String.valueOf(stop.latitude()), String.valueOf(stop.longitude())));
            data.services().forEach(service -> update(digest, service.serviceId(), service.operator(), service.category()));
            data.routeStops().forEach(route -> update(digest, route.serviceId(), route.operator(),
                    String.valueOf(route.directionId()), String.valueOf(route.stopSequence()), route.stopCode(),
                    String.valueOf(route.distanceKm())));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void update(MessageDigest digest, String... values) {
        digest.update(String.join("\u001f", java.util.Arrays.stream(values)
                .map(value -> value == null ? "" : value).toList()).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }
}
