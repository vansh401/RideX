package com.ridex.ridex_app.rating;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.ridex_app.security.AuthenticatedUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/rides/{rideId}/ratings")
@PreAuthorize("hasAnyRole('RIDER', 'DRIVER')")
@Tag(name = "Ratings", description = "Rate the other person after a completed ride")
public class RatingController {

    private final RatingService service;

    public RatingController(RatingService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Rate the other person on this ride",
            description = "1 to 5 stars and an optional comment. Only after the ride is completed, once per person (409 otherwise).")
    public RatingResponse rate(@AuthenticationPrincipal AuthenticatedUser principal,
                               @PathVariable UUID rideId,
                               @Valid @RequestBody RatingRequest request) {
        return service.rate(principal.id(), rideId, request.stars(), request.comment());
    }

    @GetMapping
    @Operation(summary = "The ratings given on this ride")
    public List<RatingResponse> list(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID rideId) {
        return service.ofRide(principal.id(), rideId);
    }
}
