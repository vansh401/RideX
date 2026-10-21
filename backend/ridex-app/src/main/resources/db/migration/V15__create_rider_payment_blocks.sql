-- Phase 7: a SAGA step. Payment is a separate service now, so when a payment fails the core cannot undo the ride (the trip
-- already happened, it cannot be "rolled back"). The compensating action is to stop that rider from booking another ride until
-- the money is settled. The core learns about the failure from the payment.failed event and records it here; payment.succeeded
-- removes it again.
--
-- The core keeps its OWN small copy of this fact because it must be able to answer "may this rider book?" instantly, even when
-- the payment service is down. There is no foreign key to payments (they live in another database); ride_id is just an id.
CREATE TABLE rider_payment_blocks (
    rider_user_id UUID          NOT NULL,
    ride_id       UUID          NOT NULL,
    amount        NUMERIC(12,2),
    currency      VARCHAR(3),
    reason        VARCHAR(255),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- One row per unpaid ride, so settling ride B never releases the block caused by ride A
    PRIMARY KEY (rider_user_id, ride_id)
);
