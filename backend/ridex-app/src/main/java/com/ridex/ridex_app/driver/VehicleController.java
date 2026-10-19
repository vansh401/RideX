package com.ridex.ridex_app.driver;

import java.util.List;
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

@RestController
@RequestMapping("/api/drivers/me/vehicles")
@PreAuthorize("hasRole('DRIVER')")
@Tag(name = "Driver vehicles", description = "Vehicles of the logged-in driver (bike, auto or cab)")
public class VehicleController {

    private final VehicleService service;

    public VehicleController(VehicleService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List my vehicles")
    public List<VehicleResponse> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return service.list(principal.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a vehicle",
            description = "At most 3. The first one becomes the active vehicle. Registration numbers are unique.")
    public VehicleResponse add(@AuthenticationPrincipal AuthenticatedUser principal,
                               @Valid @RequestBody VehicleRequest request) {
        return service.add(principal.id(), request);
    }

    @PutMapping("/{vehicleId}")
    @Operation(summary = "Correct a vehicle's details", description = "The registration number cannot change.")
    public VehicleResponse update(@AuthenticationPrincipal AuthenticatedUser principal,
                                  @PathVariable UUID vehicleId,
                                  @Valid @RequestBody VehicleRequest request) {
        return service.update(principal.id(), vehicleId, request);
    }

    @PostMapping("/{vehicleId}/activate")
    @Operation(summary = "Use this vehicle for rides", description = "Switches off the previously active vehicle.")
    public VehicleResponse activate(@AuthenticationPrincipal AuthenticatedUser principal,
                                    @PathVariable UUID vehicleId) {
        return service.activate(principal.id(), vehicleId);
    }

    @DeleteMapping("/{vehicleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a vehicle")
    public void delete(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID vehicleId) {
        service.delete(principal.id(), vehicleId);
    }
}
