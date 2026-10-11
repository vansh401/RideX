package com.ridex.ridex_app.demo;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.ridex_app.security.AuthenticatedUser;

/** TEMPORARY: lets us see role enforcement working. Delete in Phase 3. */
@RestController
public class AccessDemoController {

    // Protected by the URL rule in SecurityConfig (/api/admin/** needs ADMIN)
    @GetMapping("/api/admin/ping")
    public Map<String, String> adminPing(@AuthenticationPrincipal AuthenticatedUser principal) {
        return Map.of("area", "admin", "userId", principal.id().toString());
    }

    // Protected by method security, right next to the code
    @PreAuthorize("hasRole('DRIVER')")
    @GetMapping("/api/driver/ping")
    public Map<String, String> driverPing(@AuthenticationPrincipal AuthenticatedUser principal) {
        return Map.of("area", "driver", "userId", principal.id().toString());
    }
}
