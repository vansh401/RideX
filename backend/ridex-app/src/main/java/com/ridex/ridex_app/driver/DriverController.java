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

import com.ridex.ridex_app.security.AuthenticatedUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** The logged-in driver's own profile. There is no driver id in the URL: "me" comes from the token. */
@RestController
@RequestMapping("/api/drivers/me")
@PreAuthorize("hasRole('DRIVER')")
@Tag(name = "Driver profile", description = "Licence details and review status of the logged-in driver")
public class DriverController {

    private final DriverProfileService service;

    public DriverController(DriverProfileService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create my driver profile",
            description = "Starts as PENDING_APPROVAL. One profile per driver; licence numbers are unique.")
    public DriverProfileResponse create(@AuthenticationPrincipal AuthenticatedUser principal,
                                        @Valid @RequestBody DriverProfileRequest request) {
        return service.create(principal.id(), request);
    }

    @GetMapping
    @Operation(summary = "Get my driver profile", description = "404 until a profile has been created.")
    public DriverProfileResponse get(@AuthenticationPrincipal AuthenticatedUser principal) {
        return service.get(principal.id());
    }

    @PutMapping
    @Operation(summary = "Correct my licence details",
            description = "Only while the review is pending; otherwise 409.")
    public DriverProfileResponse update(@AuthenticationPrincipal AuthenticatedUser principal,
                                        @Valid @RequestBody DriverProfileRequest request) {
        return service.update(principal.id(), request);
    }
}
