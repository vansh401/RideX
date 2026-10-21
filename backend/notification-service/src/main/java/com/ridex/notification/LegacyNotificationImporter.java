package com.ridex.notification;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * ONE-TIME DATA MIGRATION. Before this service existed, notifications lived in the core application's database. When the
 * service starts with ridex.legacy.import-url set and its own table is still empty, it copies the old rows across.
 *
 * It is the "expand" half of the expand/contract pattern: the old table is left in place (nothing reads it any more) so a
 * rollback is possible. A later migration in the core drops it ("contract") once everyone is sure.
 * Safe to run twice: it only copies into an empty table, and rows keep their ids.
 */
@Component
public class LegacyNotificationImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyNotificationImporter.class);

    private final JdbcTemplate jdbc;
    private final String url;
    private final String user;
    private final String password;

    public LegacyNotificationImporter(JdbcTemplate jdbc,
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
        Long existing = jdbc.queryForObject("select count(*) from notifications", Long.class);
        if (existing != null && existing > 0) {
            return;
        }
        try (Connection legacy = DriverManager.getConnection(url, user, password);
             PreparedStatement select = legacy.prepareStatement(
                     "select id, user_id, type, title, body, ride_id, created_at, read_at from notifications order by created_at");
             ResultSet rs = select.executeQuery()) {
            int copied = 0;
            while (rs.next()) {
                jdbc.update("insert into notifications (id, user_id, type, title, body, ride_id, created_at, read_at) values (?,?,?,?,?,?,?,?)",
                        rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("type"), rs.getString("title"),
                        rs.getString("body"), rs.getObject("ride_id", UUID.class), rs.getTimestamp("created_at"),
                        (Timestamp) rs.getObject("read_at"));
                copied++;
            }
            log.info("Imported {} notifications from the core database", copied);
        } catch (SQLException e) {
            // The old table may simply not exist (a fresh installation): nothing to migrate
            log.info("No legacy notifications imported: {}", e.getMessage());
        }
    }
}
