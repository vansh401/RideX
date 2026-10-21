package com.ridex.analytics;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * ONE-TIME DATA MIGRATION. The audit log, the daily counts and the dead letters used to live in the core application's
 * database. When this service starts with ridex.legacy.import-url set and its own tables are empty, it copies the old rows
 * across. The old tables stay where they are (expand/contract): a later migration in the core drops them.
 */
@Component
public class LegacyEventDataImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyEventDataImporter.class);

    private final JdbcTemplate jdbc;
    private final String url;
    private final String user;
    private final String password;

    public LegacyEventDataImporter(JdbcTemplate jdbc,
                                   @Value("${ridex.legacy.import-url:}") String url,
                                   @Value("${ridex.legacy.import-user:}") String user,
                                   @Value("${ridex.legacy.import-password:}") String password) {
        this.jdbc = jdbc;
        this.url = url;
        this.user = user;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (url.isBlank()) {
            return;
        }
        try (Connection legacy = DriverManager.getConnection(url, user, password)) {
            copy(legacy, "event_audit_log",
                    "event_id, topic, partition_no, offset_no, event_key, event_type, payload, occurred_at, received_at");
            copy(legacy, "ride_status_counts_daily", "day, status, event_count");
            copy(legacy, "dead_letter_events",
                    "source_topic, partition_no, offset_no, event_key, failed_group, error_message, payload, created_at");
        } catch (SQLException e) {
            log.info("No legacy event data imported: {}", e.getMessage());
        }
    }

    /** Copies one table, column for column, but only into an empty table (so running twice never duplicates). */
    private void copy(Connection legacy, String table, String columns) throws SQLException {
        Long existing = jdbc.queryForObject("select count(*) from " + table, Long.class);
        if (existing != null && existing > 0) {
            return;
        }
        int count = columns.split(",").length;
        String insert = "insert into " + table + " (" + columns + ") values (" + "?,".repeat(count - 1) + "?)";
        int copied = 0;
        try (PreparedStatement select = legacy.prepareStatement("select " + columns + " from " + table);
             ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                Object[] values = new Object[count];
                for (int i = 0; i < count; i++) {
                    values[i] = rs.getObject(i + 1);
                }
                jdbc.update(insert, values);
                copied++;
            }
        }
        log.info("Imported {} rows into {} from the core database", copied, table);
    }
}
