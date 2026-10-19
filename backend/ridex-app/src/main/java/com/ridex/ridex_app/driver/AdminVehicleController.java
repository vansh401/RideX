package com.ridex.ridex_app.driver;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.ridex_app.common.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/admin/vehicles")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin: vehicles", description = "All registered vehicles")
public class AdminVehicleController {

    private final VehicleService service;

    public AdminVehicleController(VehicleService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List vehicles", description = "Newest first. Optional category filter.")
    public PageResponse<AdminVehicleResponse> list(
            @RequestParam(required = false) VehicleCategory category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listForAdmin(category, page, size);
    }
}
