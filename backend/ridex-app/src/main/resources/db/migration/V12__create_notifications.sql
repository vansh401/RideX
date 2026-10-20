-- Step 5.5: in-app notifications. They are stored (so they are still there after a reload or when the
-- person was offline) AND pushed live over the WebSocket when the person is connected.

CREATE TABLE notifications (
    id         UUID         PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type       VARCHAR(40)  NOT NULL,
    title      VARCHAR(120) NOT NULL,
    body       VARCHAR(500) NOT NULL,
    ride_id    UUID         REFERENCES rides (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    read_at    TIMESTAMPTZ
);

-- "My newest notifications", one page at a time
CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at DESC);

-- "How many unread do I have?" asked on every page load. Partial: only unread rows are indexed, so the
-- index stays tiny however many old notifications pile up.
CREATE INDEX idx_notifications_user_unread ON notifications (user_id) WHERE read_at IS NULL;
