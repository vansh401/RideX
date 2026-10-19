-- Step 3.4: vehicles. One driver profile has many vehicles (one-to-many).
CREATE TABLE vehicles (
    id                  UUID         PRIMARY KEY,
    driver_profile_id   UUID         NOT NULL REFERENCES driver_profiles (id) ON DELETE CASCADE,
    category            VARCHAR(10)  NOT NULL,
    make                VARCHAR(50)  NOT NULL,
    model               VARCHAR(50)  NOT NULL,
    color               VARCHAR(30)  NOT NULL,
    -- Stored normalised: upper case, no spaces or hyphens (KA01AB1234)
    registration_number VARCHAR(15)  NOT NULL UNIQUE,
    manufacture_year    INTEGER      NOT NULL,
    -- Passenger seats, excluding the driver
    seats               INTEGER      NOT NULL,
    -- The vehicle currently used for rides. At most one per driver (see the index below).
    active              BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_vehicles_category CHECK (category IN ('BIKE', 'AUTO', 'CAB')),
    CONSTRAINT chk_vehicles_year     CHECK (manufacture_year BETWEEN 2000 AND 2100),
    CONSTRAINT chk_vehicles_seats    CHECK (seats BETWEEN 1 AND 6)
);

-- Loading "all vehicles of a driver" is the hot query; foreign keys are not indexed automatically
CREATE INDEX idx_vehicles_driver_profile ON vehicles (driver_profile_id);

-- A PARTIAL unique index: uniqueness is enforced only among rows where active is true.
-- So a driver may own many vehicles but the database itself refuses a second active one,
-- even if two requests race each other and the application code has a bug.
CREATE UNIQUE INDEX uq_vehicles_one_active_per_driver ON vehicles (driver_profile_id) WHERE active;
