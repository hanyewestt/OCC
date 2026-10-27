package com.csd.occ.ingestion.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure rule: places a simulation bus on its route from its latest progress record and times the
 * stops ahead. The approached stop keeps the simulation's prediction; later stops are estimated
 * from route distance at the scenario's assumed speed. Stale, future or off-route progress yields
 * no position rather than a guess.
 */
public final class CaptainRoutePolicy {
    public record Progress(int stopSequence, Instant estimatedArrivalAt, Instant fetchedAt, int assumedSpeedKmh) {}

    public record Plan(List<CaptainRoute.Stop> stops, CaptainRoute.Position position) {}

    public Plan plan(List<ReferenceTopologyPort.BusRouteStop> route, Optional<Progress> progress, Instant now,
                     Duration staleAfter) {
        Objects.requireNonNull(route);
        Objects.requireNonNull(progress);
        Objects.requireNonNull(now);
        if (staleAfter == null || staleAfter.isZero() || staleAfter.isNegative())
            throw new IllegalArgumentException("Freshness limit must be positive");
        int index = progress.filter(value -> isFresh(value.fetchedAt(), now, staleAfter))
                .filter(value -> value.assumedSpeedKmh() > 0)
                .map(value -> indexOf(route, value.stopSequence())).orElse(-1);
        if (index < 0) return new Plan(untimed(route), CaptainRoute.Position.unavailable());

        var value = progress.orElseThrow();
        boolean approaching = !value.estimatedArrivalAt().isBefore(now);
        int next = approaching ? index : index + 1;
        int last = approaching ? index - 1 : index;
        var stops = new ArrayList<CaptainRoute.Stop>(route.size());
        for (int position = 0; position < route.size(); position++) {
            var stop = route.get(position);
            Optional<Instant> arrival = Optional.empty();
            Optional<CaptainRoute.ArrivalKind> kind = Optional.empty();
            if (position == index && approaching) {
                arrival = Optional.of(value.estimatedArrivalAt());
                kind = Optional.of(CaptainRoute.ArrivalKind.PREDICTED);
            } else if (position > index) {
                arrival = estimate(route.get(index), stop, value).filter(time -> !time.isBefore(now));
                kind = arrival.map(time -> CaptainRoute.ArrivalKind.ESTIMATED);
            }
            stops.add(new CaptainRoute.Stop(stop, arrival, kind));
        }
        return new Plan(stops, new CaptainRoute.Position(CaptainRoute.Position.Source.SIMULATION,
                last >= 0 ? Optional.of(route.get(last).stopSequence()) : Optional.empty(),
                next < route.size() ? Optional.of(route.get(next).stopSequence()) : Optional.empty(),
                Optional.of(value.fetchedAt())));
    }

    private static Optional<Instant> estimate(ReferenceTopologyPort.BusRouteStop from,
                                              ReferenceTopologyPort.BusRouteStop to, Progress progress) {
        if (from.distanceKm() == null || to.distanceKm() == null) return Optional.empty();
        BigDecimal kilometres = to.distanceKm().subtract(from.distanceKm());
        if (kilometres.signum() < 0) return Optional.empty();
        long seconds = Math.round(kilometres.doubleValue() / progress.assumedSpeedKmh() * 3600);
        return Optional.of(progress.estimatedArrivalAt().plusSeconds(seconds));
    }

    private static int indexOf(List<ReferenceTopologyPort.BusRouteStop> route, int stopSequence) {
        for (int index = 0; index < route.size(); index++)
            if (route.get(index).stopSequence() == stopSequence) return index;
        return -1;
    }

    private static List<CaptainRoute.Stop> untimed(List<ReferenceTopologyPort.BusRouteStop> route) {
        return route.stream().map(stop -> new CaptainRoute.Stop(stop, Optional.empty(), Optional.empty())).toList();
    }

    private static boolean isFresh(Instant fetchedAt, Instant now, Duration staleAfter) {
        // At the expiry boundary the evidence is already stale, matching the bunching detector.
        return !fetchedAt.isAfter(now) && Duration.between(fetchedAt, now).compareTo(staleAfter) < 0;
    }
}
