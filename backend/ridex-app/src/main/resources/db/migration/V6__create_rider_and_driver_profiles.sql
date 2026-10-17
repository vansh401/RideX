-- Rider profile: one row per rider (1:1 with users)
CREATE TABLE rider_profiles (
    id                      UUID         PRIMARY KEY,
    user_id                 UUID         NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    emergency_contact_name  VARCHAR(100),
    emergency_contact_phone VARCHAR(20),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- A contact needs both a name and a number, or neither
    CONSTRAINT chk_rider_emergency_contact
        CHECK ((emergency_contact_name IS NULL) = (emergency_contact_phone IS NULL))
);

-- Saved places: many per rider profile (Home, Work, ...)
CREATE TABLE saved_places (
    id               UUID          PRIMARY KEY,
    rider_profile_id UUID          NOT NULL REFERENCES rider_profiles (id) ON DELETE CASCADE,
    label            VARCHAR(30)   NOT NULL,
    address          VARCHAR(255)  NOT NULL,
    latitude         NUMERIC(9, 6) NOT NULL,
    longitude        NUMERIC(9, 6) NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_saved_places_latitude  CHECK (latitude  BETWEEN -90  AND 90),
    CONSTRAINT chk_saved_places_longitude CHECK (longitude BETWEEN -180 AND 180)
);

-- "Home" and "home" are the same label for one rider. This index also serves lookups by
-- rider_profile_id (its leading column), which is what loading a profile's places needs.
CREATE UNIQUE INDEX uq_saved_places_label ON saved_places (rider_profile_id, lower(label));

-- Driver profile: licence details and where the driver is in the approval process (1:1 with users).
-- The review workflow (approve / reject) arrives in the next step.
CREATE TABLE driver_profiles (
    id              UUID        PRIMARY KEY,
    user_id         UUID        NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    license_number  VARCHAR(30) NOT NULL UNIQUE,
    license_expiry  DATE        NOT NULL,
    approval_status VARCHAR(20) NOT NULL DEFAULT 'PENDING_APPROVAL',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_driver_profiles_status
        CHECK (approval_status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED'))
);

-- Data migration: riders registered before this step get an (empty) profile too
INSERT INTO rider_profiles (id, user_id)
SELECT gen_random_uuid(), id FROM users WHERE role = 'RIDER';
