package com.ridex.ridex_app.pricing;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.ridex_app.common.LatLng;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/fares")
@PreAuthorize("hasRole('RIDER')")
@Tag(name = "Fares", description = "Price quotes before booking")
public class FareController {

    public record FareEstimateRequest(@NotNull @Valid LatLng pickup, @NotNull @Valid LatLng dropoff) {
    }

    private final PricingService pricingService;

    public FareController(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    @PostMapping("/estimate")
    @Operation(summary = "Estimate the fare for every vehicle category",
            description = "Returns a full breakdown (base, distance, time, tax) in INR for BIKE, AUTO and CAB.")
    public List<FareEstimate> estimate(@Valid @RequestBody FareEstimateRequest request) {
        return pricingService.estimateAll(request.pickup(), request.dropoff());
    }
}
