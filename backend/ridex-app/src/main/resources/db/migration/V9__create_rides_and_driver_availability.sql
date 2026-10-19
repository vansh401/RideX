-- Steps 3.5 - 3.9: rides, their audit trail, driver availability and the indexes that serve them.

-------------------------------------------------------------------------------------------------
-- Driver availability: online/offline and the last known position.
-- DB-based matching for now; Phase 4 moves positions to Redis GEO.
-------------------------------------------------------------------------------------------------
ALTER TABLE driver_profiles
    ADD COLUMN online         BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN last_latitude  NUMERIC(9, 6),
    ADD COLUMN last_longitude NUMERIC(9, 6),
    ADD COLUMN last_seen_at   TIMESTAMPTZ;

-------------------------------------------------------------------------------------------------
-- Rides
-------------------------------------------------------------------------------------------------
CREATE TABLE rides (
    id                UUID          PRIMARY KEY,
    rider_id          UUID          NOT NULL REFERENCES users (id),
    -- Filled in when a driver is offered the ride; kept after completion for history
    driver_profile_id UUID          REFERENCES driver_profiles (id),
    vehicle_id        UUID          REFERENCES vehicles (id),
    category          VARCHAR(10)   NOT NULL,
    status            VARCHAR(20)   NOT NULL,

    pickup_address    VARCHAR(255)  NOT NULL,
    pickup_latitude   NUMERIC(9, 6) NOT NULL,
    pickup_longitude  NUMERIC(9, 6) NOT NULL,
    dropoff_address   VARCHAR(255)  NOT NULL,
    dropoff_latitude  NUMERIC(9, 6) NOT NULL,
    dropoff_longitude NUMERIC(9, 6) NOT NULL,

    -- The quote the rider accepted, frozen at request time. Later price changes never alter a booked ride.
    distance_km       NUMERIC(7, 2) NOT NULL,
    duration_minutes  INTEGER       NOT NULL,
    base_fare         NUMERIC(10, 2) NOT NULL,
    distance_fare     NUMERIC(10, 2) NOT NULL,
    time_fare         NUMERIC(10, 2) NOT NULL,
    surge_multiplier  NUMERIC(4, 2)  NOT NULL,
    subtotal          NUMERIC(10, 2) NOT NULL,
    tax               NUMERIC(10, 2) NOT NULL,
    total_fare        NUMERIC(10, 2) NOT NULL,
    currency          VARCHAR(3)     NOT NULL,

    offer_expires_at  TIMESTAMPTZ,
    requested_at      TIMESTAMPTZ   NOT NULL,
    accepted_at       TIMESTAMPTZ,
    arrived_at        TIMESTAMPTZ,
    started_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    cancelled_at      TIMESTAMPTZ,
    cancelled_by      VARCHAR(10),
    cancel_reason     VARCHAR(255),

    -- Optimistic locking: two simultaneous changes to one ride cannot both win
    version           BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT chk_rides_category CHECK (category IN ('BIKE', 'AUTO', 'CAB')),
    CONSTRAINT chk_rides_status CHECK (status IN (
        'REQUESTED', 'SEARCHING_DRIVER', 'DRIVER_ASSIGNED', 'DRIVER_ACCEPTED',
        'DRIVER_ARRIVED', 'RIDE_STARTED', 'RIDE_COMPLETED', 'CANCELLED')),
    CONSTRAINT chk_rides_amounts CHECK (total_fare >= 0 AND subtotal >= 0 AND tax >= 0),
    -- A ride that has a driver must also record the vehicle used
    CONSTRAINT chk_rides_driver_vehicle CHECK ((driver_profile_id IS NULL) = (vehicle_id IS NULL))
);

-- RULE: a rider has at most ONE ride in progress. Enforced by the database (partial unique index),
-- so even two requests arriving at the same instant cannot both succeed.
CREATE UNIQUE INDEX uq_rides_one_active_per_rider ON rides (rider_id)
    WHERE status IN ('REQUESTED', 'SEARCHING_DRIVER', 'DRIVER_ASSIGNED', 'DRIVER_ACCEPTED',
                     'DRIVER_ARRIVED', 'RIDE_STARTED');

-- RULE: a driver is on at most ONE ride at a time. This is the backstop behind the matching query:
-- even if the application logic had a bug, the same driver cannot be given two rides.
CREATE UNIQUE INDEX uq_rides_one_active_per_driver ON rides (driver_profile_id)
    WHERE status IN ('DRIVER_ASSIGNED', 'DRIVER_ACCEPTED', 'DRIVER_ARRIVED', 'RIDE_STARTED');

-- Ride history queries (step 3.9): "my rides, newest first", paginated.
-- (rider_id, requested_at DESC) lets PostgreSQL read the newest rows of one rider straight from the
-- index, already sorted, with no separate sort step. See docs/ride-history-explain.md.
CREATE INDEX idx_rides_rider_requested ON rides (rider_id, requested_at DESC);
CREATE INDEX idx_rides_driver_requested ON rides (driver_profile_id, requested_at DESC)
    WHERE driver_profile_id IS NOT NULL;

-- Admin list filtered by status, newest first
CREATE INDEX idx_rides_status_requested ON rides (status, requested_at DESC);

-- The dispatch job looks for exactly two small groups of rides. Partial indexes stay tiny because
-- almost all rides are finished and are not in them.
CREATE INDEX idx_rides_offer_expiry ON rides (offer_expires_at) WHERE status = 'DRIVER_ASSIGNED';
CREATE INDEX idx_rides_searching ON rides (requested_at) WHERE status = 'SEARCHING_DRIVER';

-------------------------------------------------------------------------------------------------
-- Audit trail: every status change of every ride, who made it and when.
-------------------------------------------------------------------------------------------------
CREATE TABLE ride_status_history (
    id          UUID         PRIMARY KEY,
    -- Strict order of events, even when two changes happen in the same microsecond
    seq         BIGINT       GENERATED ALWAYS AS IDENTITY,
    ride_id     UUID         NOT NULL REFERENCES rides (id) ON DELETE CASCADE,
    from_status VARCHAR(20),
    to_status   VARCHAR(20)  NOT NULL,
    -- NULL when the system itself made the change (timeouts)
    actor_id    UUID,
    actor_role  VARCHAR(10)  NOT NULL,
    note        VARCHAR(255),
    changed_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_ride_history_actor CHECK (actor_role IN ('RIDER', 'DRIVER', 'ADMIN', 'SYSTEM'))
);

CREATE INDEX idx_ride_history_ride ON ride_status_history (ride_id, seq);

-------------------------------------------------------------------------------------------------
-- Drivers who declined or ignored a ride's offer: they are not offered the same ride again.
-------------------------------------------------------------------------------------------------
CREATE TABLE ride_rejections (
    id                UUID        PRIMARY KEY,
    ride_id           UUID        NOT NULL REFERENCES rides (id) ON DELETE CASCADE,
    driver_profile_id UUID        NOT NULL REFERENCES driver_profiles (id),
    reason            VARCHAR(10) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_ride_rejections_reason CHECK (reason IN ('REJECTED', 'TIMEOUT')),
    CONSTRAINT uq_ride_rejections UNIQUE (ride_id, driver_profile_id)
);
