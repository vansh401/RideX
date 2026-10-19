package com.ridex.ridex_app.admin;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.ridex_app.common.PageResponse;
import com.ridex.ridex_app.ride.RideResponse;
import com.ridex.ridex_app.ride.RideService;
import com.ridex.ridex_app.ride.RideStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/admin/rides")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin: rides", description = "Every ride on the platform")
public class AdminRideController {

    private final RideService rideService;

    public AdminRideController(RideService rideService) {
        this.rideService = rideService;
    }

    @GetMapping
    @Operation(summary = "List all rides", description = "Newest first. Optional status filter. Use GET /api/rides/{id} for one ride.")
    public PageResponse<RideResponse> list(@RequestParam(required = false) RideStatus status,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return rideService.historyForAdmin(status, page, size);
    }
}
