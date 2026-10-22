package com.ridex.ridex_app.safety;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.ridex.platform.geo.SurgeZones;
import com.ridex.ridex_app.common.ResourceNotFoundException;

/**
 * Two small read-only endpoints that other screens need:
 *  - the busy areas (where demand is high and the price multiplier is above 1) for drivers who want to be where the rides are;
 *  - a person's name and email for the notification service, which sends email but does not own users (service token only).
 */
@RestController
public class DriverInsightsController {

    public record Zone(String zone, double multiplier, double latitude, double longitude, double radiusKm) {
    }

    public record Contact(String fullName, String email) {
    }

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;

    public DriverInsightsController(StringRedisTemplate redis, JdbcTemplate jdbc) {
        this.redis = redis;
        this.jdbc = jdbc;
    }

    /** Busiest first. Empty when nothing is surging (the normal, quiet case). */
    @GetMapping({"/api/drivers/surge-zones", "/api/admin/surge-zones"})
    public List<Zone> busyAreas() {
        Set<String> cells = redis.opsForSet().members("surge:zones");
        List<Zone> zones = new ArrayList<>();
        if (cells != null) {
            for (String cell : cells) {
                String value = redis.opsForValue().get(SurgeZones.keyOf(cell));   // expires by itself when demand calms down
                if (value == null) {
                    continue;
                }
                try {
                    double multiplier = Double.parseDouble(value);
                    double[] center = SurgeZones.centerOf(cell);
                    if (multiplier > 1.0) {
                        zones.add(new Zone(cell, multiplier, center[0], center[1], SurgeZones.coverRadiusKm()));
                    }
                } catch (RuntimeException ignored) {
                    // a malformed value is skipped, never shown
                }
            }
        }
        zones.sort(Comparator.comparingDouble(Zone::multiplier).reversed());
        return zones.stream().limit(10).toList();
    }

    @GetMapping("/internal/users/{id}/contact")
    public Contact contact(@PathVariable UUID id) {
        return jdbc.query("select full_name, email from users where id = ?", (rs, n) -> new Contact(rs.getString("full_name"), rs.getString("email")), id).stream()
                .findFirst().orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
