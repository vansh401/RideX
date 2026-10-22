package com.ridex.ridex_app.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ridex.ridex_app.ride.ActorRole;
import com.ridex.ridex_app.ride.RideRepository;
import com.ridex.ridex_app.ride.RideStatus;
import com.ridex.ridex_app.ride.RideStatusChanged;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RideMetricsTest {

    private final RideRepository rides = mock(RideRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private static RideStatusChanged change(RideStatus from, RideStatus to) {
        return new RideStatusChanged(UUID.randomUUID(), UUID.randomUUID(), null, from, to, ActorRole.RIDER, null, Instant.now(),
                "pickup", "dropoff", null, "INR", null);
    }

    @Test
    void everyStatusHasAGaugeThatShowsHowManyRidesAreInIt() {
        when(rides.countGroupedByStatus()).thenReturn(List.<Object[]>of(
                new Object[] {RideStatus.RIDE_STARTED, 4L}, new Object[] {RideStatus.SEARCHING_DRIVER, 2L}));
        new RideMetrics(rides, registry);

        assertThat(registry.get("ridex.rides").tag("status", "RIDE_STARTED").gauge().value()).isEqualTo(4.0);
        assertThat(registry.get("ridex.rides").tag("status", "SEARCHING_DRIVER").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("ridex.rides").tag("status", "CANCELLED").gauge().value()).isZero();
    }

    @Test
    void theDatabaseIsNotAskedOnEveryScrape() {
        when(rides.countGroupedByStatus()).thenReturn(List.<Object[]>of());
        new RideMetrics(rides, registry);

        for (RideStatus status : RideStatus.values()) {
            registry.get("ridex.rides").tag("status", status.name()).gauge().value();
        }

        org.mockito.Mockito.verify(rides, org.mockito.Mockito.times(1)).countGroupedByStatus();
    }

    @Test
    void anUnreachableDatabaseGivesUnknownNotZero() {
        when(rides.countGroupedByStatus()).thenThrow(new IllegalStateException("database down"));
        new RideMetrics(rides, registry);

        assertThat(registry.get("ridex.rides").tag("status", "RIDE_STARTED").gauge().value()).isNaN();
    }

    @Test
    void statusChangesAreCountedByTheStatusTheyEnteredAndNewRidesSeparately() {
        RideMetrics metrics = new RideMetrics(rides, registry);

        metrics.onRideStatusChanged(change(RideStatus.REQUESTED, RideStatus.SEARCHING_DRIVER));   // an ordinary ride starts looking
        metrics.onRideStatusChanged(change(RideStatus.SCHEDULED, RideStatus.SEARCHING_DRIVER));   // a booked ride's time came
        metrics.onRideStatusChanged(change(RideStatus.DRIVER_ASSIGNED, RideStatus.SEARCHING_DRIVER));   // a declined offer: back to searching, NOT a new request
        metrics.onRideStatusChanged(change(RideStatus.SEARCHING_DRIVER, RideStatus.CANCELLED));

        assertThat(registry.get("ridex.rides.requested").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("ridex.ride.status.changes").tag("status", "SEARCHING_DRIVER").counter().count()).isEqualTo(3.0);
        assertThat(registry.get("ridex.ride.status.changes").tag("status", "CANCELLED").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ridex.ride.status.changes").tag("status", "RIDE_COMPLETED").counter().count()).isZero();
    }
}
