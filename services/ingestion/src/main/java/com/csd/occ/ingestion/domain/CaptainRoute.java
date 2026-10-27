package com.csd.occ.ingestion.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The signed-in captain's assigned simulation bus, its ordered route and where it is along it.
 * Every value is simulation-derived; live Bus Arrival data has no vehicle identity.
 */
public record CaptainRoute(String busId, String registrationNumber, String serviceId, int directionId,
                           List<Stop> stops, Position position) {
    public CaptainRoute {
        Objects.requireNonNull(busId);
        Objects.requireNonNull(registrationNumber);
        Objects.requireNonNull(serviceId);
        Objects.requireNonNull(position);
        stops = List.copyOf(stops);
    }

    /** How a stop time was obtained. */
    public enum ArrivalKind {
        /** The simulation's own arrival prediction for the stop being approached. */
        PREDICTED,
        /** Derived from route distance at the scenario's assumed speed. */
        ESTIMATED
    }

    public record Stop(ReferenceTopologyPort.BusRouteStop stop, Optional<Instant> arrivalAt,
                       Optional<ArrivalKind> arrivalKind) {
        public Stop {
            Objects.requireNonNull(stop);
            Objects.requireNonNull(arrivalAt);
            Objects.requireNonNull(arrivalKind);
        }
    }

    /** Route sequences of the stop most recently left and the stop being approached. */
    public record Position(Source source, Optional<Integer> lastStopSequence, Optional<Integer> nextStopSequence,
                           Optional<Instant> observedAt) {
        public enum Source {
            SIMULATION,
            /** No fresh simulation progress for this bus. */
            UNAVAILABLE
        }

        public Position {
            Objects.requireNonNull(source);
            Objects.requireNonNull(lastStopSequence);
            Objects.requireNonNull(nextStopSequence);
            Objects.requireNonNull(observedAt);
        }

        public static Position unavailable() {
            return new Position(Source.UNAVAILABLE, Optional.empty(), Optional.empty(), Optional.empty());
        }
    }
}
