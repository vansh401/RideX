package com.ridex.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * DATABASE PER SERVICE, enforced. Every service owns its own database; the only thing that may cross a service boundary is an id
 * inside an event or an API response, never a foreign key and never a table that two services both write.
 *
 * The tests read the Flyway migrations of every module, because the migrations ARE the schema.
 */
class DatabasePerServiceTest {

    private static final Pattern CREATE_TABLE = Pattern.compile("(?i)create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([a-z_][a-z0-9_]*)");
    private static final Pattern REFERENCES = Pattern.compile("(?i)references\\s+([a-z_][a-z0-9_]*)");

    /** Tables every event-driven service has its own copy of (the platform library creates them in each service's database). */
    private static final Set<String> PLATFORM_TABLES = Set.of("outbox_events", "processed_events");

    /**
     * Tables the core still has from before Phase 7. Their data now lives in the new services; the core's copy stays until the
     * "contract" step (docs/sql/phase-7-contract.sql, run by hand after the data is verified). Nothing in the core reads or
     * writes them any more. This list may only SHRINK: a new overlap fails the test.
     */
    private static final Set<String> LEGACY_TABLES_IN_CORE_PENDING_CONTRACT = Set.of(
            "notifications", "payments", "mock_gateway_charges", "event_audit_log", "ride_status_counts_daily", "dead_letter_events");

    private static final Map<String, String> MIGRATIONS = new LinkedHashMap<>();

    static {
        MIGRATIONS.put("core", "ridex-app/src/main/resources/db/migration");
        MIGRATIONS.put("notification", "notification-service/src/main/resources/db/migration");
        MIGRATIONS.put("payment", "payment-service/src/main/resources/db/migration");
        MIGRATIONS.put("analytics", "analytics-service/src/main/resources/db/migration");
    }

    private static String sqlOf(String relativeDir) throws IOException {
        Path dir = Path.of("..").resolve(relativeDir);
        assumeThat(Files.isDirectory(dir)).as(relativeDir + " exists").isTrue();
        try (Stream<Path> files = Files.list(dir)) {
            StringBuilder all = new StringBuilder();
            for (Path file : files.filter(f -> f.toString().endsWith(".sql")).sorted(Comparator.naturalOrder()).toList()) {
                // comments may mention tables ("see payments") without creating or referencing anything
                Files.readAllLines(file).stream().map(DatabasePerServiceTest::stripComment).forEach(line -> all.append(line).append('\n'));
            }
            return all.toString();
        }
    }

    private static String stripComment(String line) {
        int at = line.indexOf("--");
        return at < 0 ? line : line.substring(0, at);
    }

    private static Set<String> matches(Pattern pattern, String sql) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = pattern.matcher(sql);
        while (m.find()) {
            found.add(m.group(1).toLowerCase());
        }
        return found;
    }

    @Test
    void noForeignKeyPointsOutsideTheServicesOwnDatabase() throws IOException {
        for (Map.Entry<String, String> service : MIGRATIONS.entrySet()) {
            String sql = sqlOf(service.getValue());
            Set<String> own = matches(CREATE_TABLE, sql);
            Set<String> referenced = matches(REFERENCES, sql);

            assertThat(referenced).as("tables referenced by foreign keys in the " + service.getKey() + " database")
                    .isSubsetOf(own);
        }
    }

    @Test
    void theNewServicesTablesBelongToExactlyOneService() throws IOException {
        Map<String, Set<String>> tablesByService = new LinkedHashMap<>();
        for (Map.Entry<String, String> service : MIGRATIONS.entrySet()) {
            Set<String> tables = matches(CREATE_TABLE, sqlOf(service.getValue()));
            tables.removeAll(PLATFORM_TABLES);
            tablesByService.put(service.getKey(), tables);
        }
        Set<String> core = new LinkedHashSet<>(tablesByService.get("core"));
        core.removeAll(LEGACY_TABLES_IN_CORE_PENDING_CONTRACT);

        List<String> services = List.copyOf(tablesByService.keySet());
        for (int i = 0; i < services.size(); i++) {
            for (int j = i + 1; j < services.size(); j++) {
                Set<String> a = new LinkedHashSet<>(services.get(i).equals("core") ? core : tablesByService.get(services.get(i)));
                Set<String> b = new LinkedHashSet<>(services.get(j).equals("core") ? core : tablesByService.get(services.get(j)));
                a.retainAll(b);
                assertThat(a).as("tables shared by the " + services.get(i) + " and " + services.get(j) + " databases").isEmpty();
            }
        }
    }

    @Test
    void theLegacyListInTheCoreOnlyContainsTablesThatReallyMoved() throws IOException {
        Set<String> coreTables = matches(CREATE_TABLE, sqlOf(MIGRATIONS.get("core")));
        Set<String> movedElsewhere = new LinkedHashSet<>();
        for (Map.Entry<String, String> service : MIGRATIONS.entrySet()) {
            if (!service.getKey().equals("core")) {
                movedElsewhere.addAll(matches(CREATE_TABLE, sqlOf(service.getValue())));
            }
        }

        assertThat(LEGACY_TABLES_IN_CORE_PENDING_CONTRACT).as("legacy tables still created by the core").isSubsetOf(coreTables);
        assertThat(LEGACY_TABLES_IN_CORE_PENDING_CONTRACT).as("legacy tables that a new service now owns").isSubsetOf(movedElsewhere);
    }

    @Test
    void theCoreHasNoCodeThatStillReadsTheMovedTables() throws IOException {
        Path source = Path.of("..", "ridex-app", "src", "main", "java");
        assumeThat(Files.isDirectory(source)).isTrue();
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String code = Files.readString(file);
                for (String table : LEGACY_TABLES_IN_CORE_PENDING_CONTRACT) {
                    assertThat(code).as(file.getFileName() + " must not query the moved table " + table)
                            .doesNotContainPattern("(?i)(from|join|into|update)\\s+" + table + "\\b");
                }
            }
        }
    }
}
