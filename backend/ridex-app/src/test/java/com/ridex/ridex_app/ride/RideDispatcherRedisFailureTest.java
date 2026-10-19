package com.ridex.ridex_app.ride;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.transaction.PlatformTransactionManager;

import com.ridex.ridex_app.redis.DistributedLock;
import com.ridex.ridex_app.redis.DriverLocationIndex;

/** When Redis is down, rides wait (and are retried by the background job); nothing crashes. */
class RideDispatcherRedisFailureTest {

    private final DispatchRepository dispatchRepository = mock(DispatchRepository.class);
    private final DriverLocationIndex locations = mock(DriverLocationIndex.class);
    private final DistributedLock locks = mock(DistributedLock.class);
    private final RideDispatcher dispatcher = new RideDispatcher(
            mock(RideRepository.class), dispatchRepository, locations, locks,
            mock(RideRejectionRepository.class),
            new MatchingProperties(5, 30, 120, 90, 20, 15),
            mock(PlatformTransactionManager.class));

    @Test
    void ifRedisIsUnreachableNoDriverIsAssignedAndNothingIsThrown() {
        Ride ride = mock(Ride.class);
        when(ride.getPickupLatitude()).thenReturn(new BigDecimal("12.971599"));
        when(ride.getPickupLongitude()).thenReturn(new BigDecimal("77.594566"));
        when(locations.findNearby(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        boolean assigned = dispatcher.tryAssign(ride, Instant.now());

        assertThat(assigned).isFalse();
        verifyNoInteractions(dispatchRepository, locks);
    }
}
