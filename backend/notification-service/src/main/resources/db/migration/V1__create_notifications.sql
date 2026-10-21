-- The notification service owns its own database. There are NO foreign keys to users or rides: those tables live in
-- another service's database, so a user is just an id here. (A foreign key across services would couple their
-- databases, which is exactly what splitting them is meant to avoid.)

CREATE TABLE notifications (
    id         UUID         PRIMARY KEY,
    user_id    UUID         NOT NULL,
    type       VARCHAR(40)  NOT NULL,
    title      VARCHAR(120) NOT NULL,
    body       VARCHAR(500) NOT NULL,
    ride_id    UUID,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    read_at    TIMESTAMPTZ
);

CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_user_unread ON notifications (user_id) WHERE read_at IS NULL;
