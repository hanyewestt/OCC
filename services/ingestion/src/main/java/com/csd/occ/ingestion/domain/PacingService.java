package com.csd.occ.ingestion.domain;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Recalculates pacing metrics on each cycle and pushes the snapshot to every live subscriber. */
@Service
public class PacingService {
    private static final Logger log = LoggerFactory.getLogger(PacingService.class);

    private final BusArrivalObservationRepository arrivals;
    private final PacingConfigurationPort configuration;
    private final Clock clock;
    private final PacingPolicy policy = new PacingPolicy();
    private final AtomicReference<PacingSnapshot> latest = new AtomicReference<>();
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    public PacingService(BusArrivalObservationRepository arrivals, PacingConfigurationPort configuration,
                         Clock clock) {
        this.arrivals = arrivals;
        this.configuration = configuration;
        this.clock = clock;
    }

    /**
     * Calculates a new snapshot from the current predictions and delivers it to subscribers. Not
     * transactional on purpose: the repository read has its own read-only transaction, so no database
     * connection is held while snapshots are written to slow clients.
     */
    public PacingSnapshot refresh() {
        var inputs = arrivals.findPacingArrivals().stream().map(PacingService::arrival).toList();
        var now = clock.instant();
        var snapshot = new PacingSnapshot(now, policy.evaluate(inputs, now, configuration.targetHeadway(),
                configuration.tooCloseRatio(), configuration.tooFarRatio(), configuration.staleAfter()));
        latest.set(snapshot);
        subscriptions.forEach(subscription -> deliver(subscription, snapshot));
        return snapshot;
    }

    public PacingSnapshot latest() {
        return Optional.ofNullable(latest.get()).orElseThrow(PacingUnavailableException::new);
    }

    /**
     * Registers a receiver for every future snapshot. The current snapshot, when one exists, is
     * delivered immediately so a new subscriber does not wait a full cycle.
     */
    public Subscription subscribe(Consumer<PacingSnapshot> receiver) {
        var subscription = new Subscription(receiver);
        synchronized (subscriptions) {
            if (subscriptions.size() >= configuration.maxSubscribers())
                throw new PacingSubscriberLimitException(configuration.maxSubscribers());
            subscriptions.add(subscription);
        }
        Optional.ofNullable(latest.get()).ifPresent(snapshot -> deliver(subscription, snapshot));
        return subscription;
    }

    public int subscriberCount() {
        return subscriptions.size();
    }

    private void deliver(Subscription subscription, PacingSnapshot snapshot) {
        try {
            subscription.receiver.accept(snapshot);
        } catch (RuntimeException exception) {
            // A disconnected receiver must not stop delivery to the others.
            log.debug("Dropping pacing subscriber after a failed delivery: {}", exception.getMessage());
            subscription.cancel();
        }
    }

    private static PacingArrival arrival(PacingArrivalView view) {
        return new PacingArrival(view.getClassification(), view.getStopCode(), view.getServiceId(),
                view.getDirectionId(), view.getEstimatedArrivalAt(), view.getFetchedAt(),
                Optional.ofNullable(view.getVehicleReference()));
    }

    public final class Subscription {
        private final Consumer<PacingSnapshot> receiver;

        private Subscription(Consumer<PacingSnapshot> receiver) {
            this.receiver = receiver;
        }

        public void cancel() {
            subscriptions.remove(this);
        }
    }
}
