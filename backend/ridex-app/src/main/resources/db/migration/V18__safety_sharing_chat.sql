-- Phase 15: safety (SOS, trip sharing), in-trip chat, and an email address for the emergency contact.

-- A contact can now also be reached by email (the SOS alert and the live trip link are emailed to it). Optional.
ALTER TABLE rider_profiles ADD COLUMN emergency_contact_email VARCHAR(160);

-- A trip share: an unguessable token anyone holding the link can use to follow the trip (read-only, no login). It expires.
CREATE TABLE ride_shares (
    ride_id    UUID         PRIMARY KEY REFERENCES rides (id) ON DELETE CASCADE,
    token      VARCHAR(48)  NOT NULL UNIQUE,
    created_by UUID         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ  NOT NULL
);

-- An SOS raised from a ride by its rider or driver. Administrators acknowledge and resolve it.
CREATE TABLE sos_alerts (
    id              UUID          PRIMARY KEY,
    ride_id         UUID          NOT NULL REFERENCES rides (id) ON DELETE CASCADE,
    raised_by       UUID          NOT NULL,
    raised_by_role  VARCHAR(10)   NOT NULL CHECK (raised_by_role IN ('RIDER', 'DRIVER')),
    latitude        NUMERIC(9,6),
    longitude       NUMERIC(9,6),
    status          VARCHAR(14)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    note            VARCHAR(300),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    handled_by      UUID,
    handled_at      TIMESTAMPTZ
);
CREATE INDEX idx_sos_status_created ON sos_alerts (status, created_at DESC);
-- One open alert per person per ride: pressing the button twice does not raise two emergencies
CREATE UNIQUE INDEX ux_sos_open_per_person ON sos_alerts (ride_id, raised_by) WHERE status = 'OPEN';

-- Messages between the rider and the driver of one ride.
CREATE TABLE ride_messages (
    id          UUID         PRIMARY KEY,
    ride_id     UUID         NOT NULL REFERENCES rides (id) ON DELETE CASCADE,
    sender_id   UUID         NOT NULL,
    sender_role VARCHAR(10)  NOT NULL CHECK (sender_role IN ('RIDER', 'DRIVER')),
    body        VARCHAR(500) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_ride_messages ON ride_messages (ride_id, created_at);
