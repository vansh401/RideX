package com.ridex.pricing;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
public class FareController {

    public record FareEstimateRequest(@NotNull @Valid LatLng pickup, @NotNull @Valid LatLng dropoff) {
    }

    public record QuoteRequest(@NotNull @Valid LatLng pickup, @NotNull @Valid LatLng dropoff, @NotNull VehicleCategory category) {
    }

    private final PricingService pricingService;

    public FareController(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    /** For riders (called by the browser): a quote for every vehicle category so they can compare. */
    @PostMapping("/api/fares/estimate")
    public List<FareEstimate> estimate(@Valid @RequestBody FareEstimateRequest request) {
        return pricingService.estimateAll(request.pickup(), request.dropoff());
    }

    /** For other services (called by the core when a ride is requested): one exact quote. Protected by the service token. */
    @PostMapping("/internal/quotes")
    public FareEstimate quote(@Valid @RequestBody QuoteRequest request) {
        return pricingService.estimate(request.pickup(), request.dropoff(), request.category());
    }
}
