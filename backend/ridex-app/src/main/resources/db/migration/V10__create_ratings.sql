-- Step 3.10: ratings. After a completed ride, rider and driver can each rate the other once.

CREATE TABLE ratings (
    id         UUID         PRIMARY KEY,
    ride_id    UUID         NOT NULL REFERENCES rides (id) ON DELETE CASCADE,
    rater_id   UUID         NOT NULL REFERENCES users (id),
    ratee_id   UUID         NOT NULL REFERENCES users (id),
    rater_role VARCHAR(10)  NOT NULL,
    stars      INTEGER      NOT NULL,
    comment    VARCHAR(500),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_ratings_stars      CHECK (stars BETWEEN 1 AND 5),
    CONSTRAINT chk_ratings_rater_role CHECK (rater_role IN ('RIDER', 'DRIVER')),
    CONSTRAINT chk_ratings_not_self   CHECK (rater_id <> ratee_id),
    -- One rating per person per ride. The database enforces it, so two clicks of the same
    -- button (or two racing requests) can never create a duplicate.
    CONSTRAINT uq_ratings_one_per_rater UNIQUE (ride_id, rater_id)
);

-- "Latest ratings received by this person"
CREATE INDEX idx_ratings_ratee ON ratings (ratee_id, created_at DESC);

-- Running totals, so an average is one division instead of a scan over every rating.
-- average = rating_sum / rating_count. Updated with "SET x = x + n" (atomic), never read-modify-write.
ALTER TABLE driver_profiles
    ADD COLUMN rating_sum   INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN rating_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE rider_profiles
    ADD COLUMN rating_sum   INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN rating_count INTEGER NOT NULL DEFAULT 0;
