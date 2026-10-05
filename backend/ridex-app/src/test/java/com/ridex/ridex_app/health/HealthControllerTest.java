package com.ridex.ridex_app.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class HealthControllerTest {

    @Test
    void healthReturnsUpAndAppNameFromDatabase() {
        // Arrange: a fake JdbcTemplate that answers "RideX" to any SQL string
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class)))
                .thenReturn("RideX");
        HealthController controller = new HealthController(jdbcTemplate);

        // Act
        Map<String, String> result = controller.health();

        // Assert
        assertThat(result)
                .containsEntry("status", "UP")
                .containsEntry("app", "RideX");
        verify(jdbcTemplate).queryForObject(anyString(), eq(String.class));
    }
}