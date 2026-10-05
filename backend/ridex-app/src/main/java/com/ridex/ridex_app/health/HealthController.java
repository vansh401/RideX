package com.ridex.ridex_app.health;

import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {
    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        String appName = jdbcTemplate.queryForObject(
                "SELECT info_value FROM app_info WHERE info_key = 'app_name'",
                String.class);
        return Map.of("status", "UP", "app", appName);
    }

}
