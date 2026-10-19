package com.ridex.ridex_app.driver;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.ridex_app.common.LatLng;
import com.ridex.ridex_app.security.AuthenticatedUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/drivers/me/availability")
@PreAuthorize("hasRole('DRIVER')")
@Tag(name = "Driver availability", description = "Go online or offline and share your location")
public class DriverAvailabilityController {

    private final DriverAvailabilityService service;

    public DriverAvailabilityController(DriverAvailabilityService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Am I online?")
    public AvailabilityResponse status(@AuthenticationPrincipal AuthenticatedUser principal) {
        return service.status(principal.id());
    }

    @PostMapping("/online")
    @Operation(summary = "Go online at this location",
            description = "Requires an APPROVED profile and an active vehicle; otherwise 409.")
    public AvailabilityResponse goOnline(@AuthenticationPrincipal AuthenticatedUser principal,
                                         @Valid @RequestBody LatLng position) {
        return service.goOnline(principal.id(), position);
    }

    @PostMapping("/offline")
    @Operation(summary = "Go offline", description = "Never interrupts a ride that is already in progress.")
    public AvailabilityResponse goOffline(@AuthenticationPrincipal AuthenticatedUser principal) {
        return service.goOffline(principal.id());
    }

    @PutMapping("/location")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Share my current location", description = "Call every few seconds while online. 409 if offline.")
    public void location(@AuthenticationPrincipal AuthenticatedUser principal, @Valid @RequestBody LatLng position) {
        service.updateLocation(principal.id(), position);
    }
}
