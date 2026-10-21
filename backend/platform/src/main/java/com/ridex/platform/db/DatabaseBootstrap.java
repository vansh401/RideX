package com.ridex.platform.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * DATABASE PER SERVICE, made easy for development: a service creates its own database on first start if it does not
 * exist yet. (In production a database is created by whoever operates the platform, with its own user and permissions;
 * this convenience is only used when the property ridex.database.auto-create=true.)
 *
 * Call it at the very start of main(), before Spring connects.
 */
public final class DatabaseBootstrap {

    private DatabaseBootstrap() {
    }

    /** @param jdbcUrl for example jdbc:postgresql://localhost:5432/payment_db */
    public static void ensureDatabaseExists(String jdbcUrl, String user, String password) {
        int slash = jdbcUrl.lastIndexOf('/');
        String base = jdbcUrl.substring(0, slash + 1);
        String name = jdbcUrl.substring(slash + 1);
        int query = name.indexOf('?');
        if (query >= 0) {
            name = name.substring(0, query);
        }
        if (!name.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("Unsafe database name: " + name);
        }
        try (Connection admin = DriverManager.getConnection(base + "postgres", user, password)) {
            try (PreparedStatement exists = admin.prepareStatement("select 1 from pg_database where datname = ?")) {
                exists.setString(1, name);
                try (ResultSet rs = exists.executeQuery()) {
                    if (rs.next()) {
                        return;
                    }
                }
            }
            admin.createStatement().execute("create database " + name);   // the name was validated above
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create database " + name + ": " + e.getMessage(), e);
        }
    }
}
