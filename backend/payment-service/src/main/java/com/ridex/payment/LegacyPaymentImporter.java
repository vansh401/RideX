package com.ridex.payment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * ONE-TIME DATA MIGRATION. Before this service existed, payments (and the mock gateway's books) lived in the core
 * application's database. When this service starts with ridex.legacy.import-url set and its own tables are empty, it copies
 * the old rows across, keeping their ids. The old tables stay in place (nothing reads them any more) until a later "contract"
 * migration in the core drops them: the expand/contract pattern, so a rollback stays possible.
 */
@Component
public class LegacyPaymentImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyPaymentImporter.class);

    private final JdbcTemplate jdbc;
    private final String url;
    private final String user;
    private final String password;

    public LegacyPaymentImporter(JdbcTemplate jdbc,
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
        Long existing = jdbc.queryForObject("select count(*) from payments", Long.class);
        if (existing != null && existing > 0) {
            return;
        }
        try (Connection legacy = DriverManager.getConnection(url, user, password)) {
            int payments = 0;
            try (PreparedStatement select = legacy.prepareStatement("""
                    select id, ride_id, rider_user_id, driver_user_id, amount, currency, status, attempts, idempotency_key,
                           gateway_reference, failure_reason, created_at, updated_at from payments order by created_at""");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    jdbc.update("""
                            insert into payments (id, ride_id, rider_user_id, driver_user_id, amount, currency, status, attempts,
                                                  idempotency_key, gateway_reference, failure_reason, created_at, updated_at)
                            values (?,?,?,?,?,?,?,?,?,?,?,?,?)
                            """, rs.getObject("id", UUID.class), rs.getObject("ride_id", UUID.class), rs.getObject("rider_user_id", UUID.class),
                            rs.getObject("driver_user_id", UUID.class), rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("status"),
                            rs.getInt("attempts"), rs.getString("idempotency_key"), rs.getString("gateway_reference"), rs.getString("failure_reason"),
                            rs.getTimestamp("created_at"), rs.getTimestamp("updated_at"));
                    payments++;
                }
            }
            int charges = 0;
            try (PreparedStatement select = legacy.prepareStatement(
                    "select idempotency_key, amount, currency, success, reference, failure_reason, created_at from mock_gateway_charges");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    jdbc.update("insert into mock_gateway_charges (idempotency_key, amount, currency, success, reference, failure_reason, created_at) values (?,?,?,?,?,?,?)",
                            rs.getString("idempotency_key"), rs.getBigDecimal("amount"), rs.getString("currency"), rs.getBoolean("success"),
                            rs.getString("reference"), rs.getString("failure_reason"), rs.getTimestamp("created_at"));
                    charges++;
                }
            }
            log.info("Imported {} payments and {} gateway charges from the core database", payments, charges);
        } catch (SQLException e) {
            log.info("No legacy payments imported: {}", e.getMessage());
        }
    }
}
