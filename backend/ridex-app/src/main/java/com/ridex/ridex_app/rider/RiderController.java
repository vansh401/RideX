package com.ridex.ridex_app.rider;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.ridex_app.security.AuthenticatedUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** The logged-in rider's own profile. There is no rider id in any URL: "me" comes from the token. */
@RestController
@RequestMapping("/api/riders/me")
@PreAuthorize("hasRole('RIDER')")
@Tag(name = "Rider profile", description = "Emergency contact and saved places of the logged-in rider")
public class RiderController {

    private final RiderProfileService service;

    public RiderController(RiderProfileService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Get my rider profile, including saved places")
    public RiderProfileResponse get(@AuthenticationPrincipal AuthenticatedUser principal) {
        return service.get(principal.id());
    }

    @PutMapping
    @Operation(summary = "Set or clear my emergency contact",
            description = "Send both fields to set it, or neither to clear it.")
    public RiderProfileResponse update(@AuthenticationPrincipal AuthenticatedUser principal,
                                       @Valid @RequestBody UpdateRiderProfileRequest request) {
        return service.updateEmergencyContact(principal.id(), request);
    }

    @PostMapping("/places")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Save a place (Home, Work, ...)",
            description = "At most 10 places; labels are unique per rider, ignoring case.")
    public SavedPlaceResponse addPlace(@AuthenticationPrincipal AuthenticatedUser principal,
                                       @Valid @RequestBody SavedPlaceRequest request) {
        return service.addPlace(principal.id(), request);
    }

    @DeleteMapping("/places/{placeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete one of my saved places",
            description = "404 if the place does not exist or belongs to someone else.")
    public void removePlace(@AuthenticationPrincipal AuthenticatedUser principal,
                            @PathVariable UUID placeId) {
        service.removePlace(principal.id(), placeId);
    }
}
