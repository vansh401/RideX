package com.ridex.ridex_app.observability;

import java.util.EnumMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.ridex.platform.web.LogContext;
import com.ridex.ridex_app.ride.RideRepository;
import com.ridex.ridex_app.ride.RideStatus;
import com.ridex.ridex_app.ride.RideStatusChanged;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The business numbers of the platform, next to the technical ones Spring publishes by itself (requests, database pool, JVM, Kafka).
 *
 *   ridex_rides{status}                       GAUGE    how many rides are in each status right now ("active rides" = the non-final ones)
 *   ridex_ride_status_changes_total{status}   COUNTER  how many times a ride entered each status (only goes up; use rate())
 *   ridex_rides_requested_total               COUNTER  rides requested
 *
 * A gauge is a value that goes up and down (rides in progress); a counter only goes up and is read as a rate ("rides per minute").
 * The counters are updated AFTER the transaction committed, so a rolled-back change is never counted.
 */
@Component
public class RideMetrics {

    private static final Logger log = LoggerFactory.getLogger(RideMetrics.class);
    private static final long MAX_AGE_MS = 10_000;

    private final RideRepository rides;
    private final MeterRegistry registry;
    private final Map<RideStatus, Long> counts = new EnumMap<>(RideStatus.class);
    private volatile long readAt = 0;
    private final Counter requested;

    public RideMetrics(RideRepository rides, MeterRegistry registry) {
        this.rides = rides;
        this.registry = registry;
        requested = Counter.builder("ridex.rides.requested").description("Rides requested").register(registry);
        for (RideStatus status : RideStatus.values()) {
            Gauge.builder("ridex.rides", this, m -> m.count(status))
                    .description("Rides currently in each status")
                    .tag("status", status.name())
                    .strongReference(true)   // the registry alone would only hold this object weakly
                    .register(registry);
            Counter.builder("ridex.ride.status.changes").description("Times a ride entered this status")
                    .tag("status", status.name()).register(registry);   // visible as 0 from the start
        }
    }

    private synchronized double count(RideStatus status) {
        long now = System.currentTimeMillis();
        if (now - readAt > MAX_AGE_MS) {
            try {
                counts.clear();
                for (Object[] row : rides.countGroupedByStatus()) {
                    counts.put((RideStatus) row[0], (Long) row[1]);
                }
                readAt = now;
            } catch (RuntimeException e) {
                return Double.NaN;   // database unreachable: "unknown" is more honest than the last value
            }
        }
        return counts.getOrDefault(status, 0L);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRideStatusChanged(RideStatusChanged e) {
        try (LogContext.Scope ignored = LogContext.ride(e.rideId())) {
            registry.counter("ridex.ride.status.changes", "status", e.to().name()).increment();
            if (e.to() == RideStatus.SEARCHING_DRIVER && (e.from() == RideStatus.REQUESTED || e.from() == RideStatus.SCHEDULED)) {
                requested.increment();   // the first time a ride starts looking for a driver (a ride is never "new" in an event: its first line is history only)
            }
            // The business story of a ride, one line per step. Ids only: no names, addresses or fares.
            log.info("Ride status changed: {} -> {}", e.from() == null ? "(new)" : e.from(), e.to());
        }
    }
}
