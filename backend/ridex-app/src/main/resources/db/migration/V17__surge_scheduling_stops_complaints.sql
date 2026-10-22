-- Phase 14: surge zones, scheduled rides, stops, coupons on rides, and complaints.

-- =====================================================================================================================
-- RIDES: the zone of the pickup (for surge), the coupon the rider asked for, the time of a ride booked ahead, and stop progress
-- =====================================================================================================================
ALTER TABLE rides
    ADD COLUMN pickup_cell   VARCHAR(30),
    ADD COLUMN coupon_code   VARCHAR(40),
    -- A ride booked ahead: the moment the rider wants to be picked up. NULL for an ordinary ride.
    ADD COLUMN scheduled_for TIMESTAMPTZ,
    -- When the search for a driver actually began (the request, or for a booked ride the moment it was promoted). The "no driver found" timeout counts from here.
    ADD COLUMN search_started_at TIMESTAMPTZ,
    ADD COLUMN stops_total   INTEGER NOT NULL DEFAULT 0 CHECK (stops_total BETWEEN 0 AND 3),
    ADD COLUMN stops_done    INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_rides_stops_done CHECK (stops_done BETWEEN 0 AND stops_total);

-- A new status: SCHEDULED (booked, waiting for its time). It is NOT in the "one active ride per rider" index below (which lists its
-- statuses explicitly), so a rider can have rides booked ahead and still take a ride now.
ALTER TABLE rides DROP CONSTRAINT chk_rides_status;
ALTER TABLE rides ADD CONSTRAINT chk_rides_status CHECK (status IN (
    'SCHEDULED', 'REQUESTED', 'SEARCHING_DRIVER', 'DRIVER_ASSIGNED', 'DRIVER_ACCEPTED',
    'DRIVER_ARRIVED', 'RIDE_STARTED', 'RIDE_COMPLETED', 'CANCELLED'));

-- "Which booked rides are due?" Partial: only waiting rides are indexed.
CREATE INDEX idx_rides_scheduled ON rides (scheduled_for) WHERE status = 'SCHEDULED';
-- Demand measurement for surge: "how many rides were requested in this zone in the last minutes?"
CREATE INDEX idx_rides_cell_requested ON rides (pickup_cell, requested_at) WHERE pickup_cell IS NOT NULL AND scheduled_for IS NULL;

-- =====================================================================================================================
-- STOPS: places to stop on the way, in order
-- =====================================================================================================================
CREATE TABLE ride_stops (
    id         UUID          PRIMARY KEY,
    ride_id    UUID          NOT NULL REFERENCES rides (id) ON DELETE CASCADE,
    position   INTEGER       NOT NULL CHECK (position BETWEEN 1 AND 3),
    address    VARCHAR(255)  NOT NULL,
    latitude   NUMERIC(9,6)  NOT NULL,
    longitude  NUMERIC(9,6)  NOT NULL,
    arrived_at TIMESTAMPTZ,
    UNIQUE (ride_id, position)
);

-- =====================================================================================================================
-- COMPLAINTS: a person says something went wrong with a ride; an administrator handles it
-- =====================================================================================================================
CREATE TABLE complaints (
    id              UUID          PRIMARY KEY,
    ride_id         UUID          NOT NULL REFERENCES rides (id),
    filed_by        UUID          NOT NULL REFERENCES users (id),
    filed_by_role   VARCHAR(10)   NOT NULL CHECK (filed_by_role IN ('RIDER', 'DRIVER')),
    category        VARCHAR(20)   NOT NULL CHECK (category IN ('OVERCHARGED', 'RUDE_BEHAVIOUR', 'UNSAFE_DRIVING', 'ROUTE_ISSUE', 'VEHICLE_ISSUE', 'PAYMENT_ISSUE', 'LOST_ITEM', 'OTHER')),
    description     VARCHAR(1000) NOT NULL,
    status          VARCHAR(10)   NOT NULL CHECK (status IN ('OPEN', 'IN_REVIEW', 'RESOLVED', 'REJECTED')),
    assigned_to     UUID          REFERENCES users (id),
    resolution_note VARCHAR(1000),
    refund_amount   NUMERIC(10,2) CHECK (refund_amount >= 0),
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    resolved_at     TIMESTAMPTZ,
    version         BIGINT        NOT NULL DEFAULT 0,
    -- A resolved or rejected complaint always says why
    CONSTRAINT chk_complaint_closed CHECK (status NOT IN ('RESOLVED', 'REJECTED') OR (resolution_note IS NOT NULL AND resolved_at IS NOT NULL))
);
-- One OPEN or IN_REVIEW complaint per person per ride: filing again before it is handled is refused by the database itself
CREATE UNIQUE INDEX uq_complaint_one_open ON complaints (ride_id, filed_by) WHERE status IN ('OPEN', 'IN_REVIEW');
CREATE INDEX idx_complaints_status_created ON complaints (status, created_at DESC);
CREATE INDEX idx_complaints_filer ON complaints (filed_by, created_at DESC);

CREATE TABLE complaint_comments (
    id              UUID          PRIMARY KEY,
    complaint_id    UUID          NOT NULL REFERENCES complaints (id) ON DELETE CASCADE,
    author_id       UUID          NOT NULL REFERENCES users (id),
    author_role     VARCHAR(10)   NOT NULL,
    message         VARCHAR(1000) NOT NULL,
    -- Internal notes are for administrators only; the person who complained sees the rest
    internal        BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_complaint_comments ON complaint_comments (complaint_id, created_at);
