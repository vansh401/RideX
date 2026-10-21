package com.ridex.ridex_app.pricing;

import com.ridex.ridex_app.common.LatLng;
import com.ridex.ridex_app.driver.VehicleCategory;

/**
 * How the core asks "what does this trip cost?". Pricing is now a SEPARATE service, so this is a network call that can be
 * slow or fail; the interface keeps the ride code unaware of that, and lets tests plug in a fake.
 */
public interface PricingClient {

    /**
     * @throws com.ridex.ridex_app.common.BadRequestException      the trip itself is not acceptable (too short, too long)
     * @throws com.ridex.platform.web.ServiceUnavailableException  the pricing service is not answering (HTTP 503)
     */
    FareQuote quote(LatLng pickup, LatLng dropoff, VehicleCategory category);
}
