package com.ridex.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Observability rules, as tests. Monitoring configuration is easy to let rot: a metric is renamed in the code and the alert that watched
 * it silently never fires again. These tests connect the three: the code that publishes metrics, the alert rules, and the dashboard.
 */
class ObservabilityPolicyTest {

    private static final Path BACKEND = Path.of("..");
    private static final Path OBS = Path.of("..", "..", "infrastructure", "observability");
    private static final List<String> JAVA_SERVICES = List.of("ridex-app", "notification-service", "payment-service", "pricing-service", "analytics-service");

    /** Metric names the code publishes ("ridex.outbox.pending" in Java becomes ridex_outbox_pending in Prometheus). */
    private static final Set<String> PUBLISHED = new HashSet<>();

    @BeforeAll
    static void collectPublishedMetrics() throws IOException {
        assumeThat(Files.isDirectory(OBS)).as("run from the architecture-tests module").isTrue();
        Pattern name = Pattern.compile("\"(ridex\\.[a-z.]+)\"");
        try (Stream<Path> walk = Files.walk(BACKEND)) {
            for (Path file : walk.filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/") && p.toString().endsWith(".java")).toList()) {
                Matcher m = name.matcher(Files.readString(file));
                while (m.find()) {
                    String prometheus = m.group(1).replace('.', '_');
                    PUBLISHED.add(prometheus);
                    PUBLISHED.add(prometheus + "_total");   // counters get this suffix
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> alertRules() throws IOException {
        Map<String, Object> doc = new Yaml().load(Files.readString(OBS.resolve("prometheus/alerts.yml")));
        List<Map<String, Object>> rules = new ArrayList<>();
        for (Map<String, Object> group : (List<Map<String, Object>>) doc.get("groups")) {
            rules.addAll((List<Map<String, Object>>) group.get("rules"));
        }
        return rules;
    }

    private static Set<String> ridexMetricsIn(String expression) {
        Set<String> found = new HashSet<>();
        Matcher m = Pattern.compile("\\bridex_[a-z_]+\\b").matcher(expression);
        while (m.find()) {
            found.add(m.group());
        }
        return found;
    }

    // ------------------------------------------------------------------ the services publish metrics, safely

    @Test
    void everyServiceExposesPrometheusMetricsTaggedWithItsNameAndSamplesTraces() throws IOException {
        for (String service : JAVA_SERVICES) {
            String properties = Files.readString(BACKEND.resolve(service + "/src/main/resources/application.properties"));
            assertThat(properties).as(service).containsPattern("management\\.endpoints\\.web\\.exposure\\.include=.*prometheus")
                    .contains("management.metrics.tags.application=${spring.application.name}")
                    .contains("management.tracing.sampling.probability=")
                    .contains("management.otlp.metrics.export.enabled=false")
                    .contains("percentiles-histogram.http.server.requests=true");
        }
        String gateway = Files.readString(BACKEND.resolve("api-gateway/src/main/resources/application.yml"));
        assertThat(gateway).contains("include: health,info,prometheus").contains("application: ${spring.application.name}")
                .contains("probability: ${TRACING_SAMPLING").contains("observability:");
    }

    @Test
    void theMetricsEndpointIsOpenToPrometheusInsideTheNetworkButNeverRoutedOutside() throws IOException {
        for (String service : List.of("analytics", "notification", "payment", "pricing")) {
            assertThat(Files.readString(BACKEND.resolve(service + "-service/src/main/java/com/ridex/" + service + "/SecurityConfig.java")))
                    .as(service).contains("/actuator/prometheus");
        }
        assertThat(Files.readString(BACKEND.resolve("ridex-app/src/main/java/com/ridex/ridex_app/config/SecurityConfig.java"))).contains("/actuator/prometheus");

        String gateway = Files.readString(BACKEND.resolve("api-gateway/src/main/resources/application.yml"));
        assertThat(gateway).as("the gateway still blocks the rest of /actuator from outside").contains("- \"/actuator/**\"");
        assertThat(gateway).contains("\"GET:/actuator/prometheus\"");

        // The ingress and the website's nginx send /api and /ws to the gateway, and never /actuator
        String ingress = Files.readString(Path.of("..", "..", "infrastructure", "k8s", "base", "ingress.yaml"));
        assertThat(ingress).doesNotContain("/actuator");
    }

    // ------------------------------------------------------------------ alerts

    @Test
    void everyAlertHasASeverityASummaryAnActionAndWaitsBeforeFiring() throws IOException {
        List<Map<String, Object>> rules = alertRules();
        assertThat(rules).isNotEmpty();
        Set<String> names = new HashSet<>();
        for (Map<String, Object> rule : rules) {
            String alert = (String) rule.get("alert");
            assertThat(names.add(alert)).as("alert name " + alert + " is unique").isTrue();
            assertThat(rule).as(alert).containsKey("for");
            @SuppressWarnings("unchecked") Map<String, Object> labels = (Map<String, Object>) rule.get("labels");
            @SuppressWarnings("unchecked") Map<String, Object> annotations = (Map<String, Object>) rule.get("annotations");
            assertThat(labels.get("severity")).as(alert + " severity").isIn("critical", "warning");
            assertThat(annotations).as(alert).containsKeys("summary", "description", "action");
            assertThat((String) annotations.get("action")).as(alert + ": an alert without an action is a graph").isNotBlank();
        }
    }

    @Test
    void ratioAlertsRequireRealTrafficBeforeTheyFire() throws IOException {
        for (Map<String, Object> rule : alertRules()) {
            String expression = (String) rule.get("expr");
            if (expression.contains(" / ") && expression.contains("rate(")) {
                assertThat(expression).as(rule.get("alert") + ": 1 failure out of 2 requests is not an outage").containsPattern("\\band\\b");
            }
        }
    }

    @Test
    void everyApplicationMetricAnAlertOrTheDashboardUsesIsReallyPublishedByTheCode() throws IOException {
        Set<String> used = new HashSet<>();
        for (Map<String, Object> rule : alertRules()) {
            used.addAll(ridexMetricsIn((String) rule.get("expr")));
        }
        used.addAll(ridexMetricsIn(Files.readString(OBS.resolve("grafana/dashboards/ridex-overview.json"))));
        assertThat(used).as("the alerts and the dashboard use the application's own metrics").isNotEmpty();
        assertThat(PUBLISHED).as("a metric used in monitoring but not published in the code (renamed? typo?)").containsAll(used);
    }

    @Test
    void everyMetricThePlatformPublishesIsWatchedByTheDashboardOrAnAlert() throws IOException {
        // the opposite direction: a metric nobody looks at is cost without value
        String monitoring = Files.readString(OBS.resolve("grafana/dashboards/ridex-overview.json")) + Files.readString(OBS.resolve("prometheus/alerts.yml"));
        for (String metric : Set.of("ridex_rides", "ridex_outbox_pending", "ridex_dead_letters_total", "ridex_payments_total", "ridex_ride_status_changes_total")) {
            assertThat(monitoring).as(metric).contains(metric);
        }
    }

    // ------------------------------------------------------------------ the dashboard

    @Test
    @SuppressWarnings("unchecked")
    void theDashboardIsValidAndEveryPanelExplainsItselfAndUsesTheProvisionedDataSource() throws IOException {
        Map<String, Object> dashboard = new Yaml().load(Files.readString(OBS.resolve("grafana/dashboards/ridex-overview.json")));   // JSON is YAML
        assertThat(dashboard).containsKeys("uid", "title", "panels", "templating");
        String datasources = Files.readString(OBS.resolve("grafana/provisioning/datasources/datasources.yml"));
        Set<Object> ids = new HashSet<>();
        for (Map<String, Object> panel : (List<Map<String, Object>>) dashboard.get("panels")) {
            String title = (String) panel.get("title");
            assertThat(ids.add(panel.get("id"))).as("panel id of " + title + " is unique").isTrue();
            assertThat((String) panel.get("description")).as(title + " says what to conclude from it").isNotBlank();
            Map<String, Object> datasource = (Map<String, Object>) panel.get("datasource");
            assertThat(datasources).as(title).contains("uid: " + datasource.get("uid"));
            assertThat((List<Object>) panel.get("targets")).as(title).isNotEmpty();
        }
    }

    // ------------------------------------------------------------------ the stack itself

    @Test
    void monitoringImagesArePinnedPortsAreLocalAndNoPasswordIsInTheFile() throws IOException {
        String compose = Files.readString(OBS.resolve("docker-compose.observability.yml"));
        Matcher images = Pattern.compile("(?m)^\\s+image:\\s+(\\S+)").matcher(compose);
        int count = 0;
        while (images.find()) {
            count++;
            assertThat(images.group(1)).doesNotContain(":latest").matches(".+:[v]?\\d+\\.\\d+\\.\\d+");
        }
        assertThat(count).isEqualTo(4);
        Matcher ports = Pattern.compile("(?m)^\\s+- \"([^\"]+:\\d+:\\d+)\"").matcher(compose);
        while (ports.find()) {
            assertThat(ports.group(1)).as("published ports bind to this computer only").startsWith("127.0.0.1:");
        }
        assertThat(compose).as("the Grafana admin password comes from a secret file").contains("GF_SECURITY_ADMIN_PASSWORD__FILE")
                .doesNotContainPattern("(?i)GF_SECURITY_ADMIN_PASSWORD:");
        assertThat(Files.readString(Path.of("..", "..", ".gitignore"))).contains("infrastructure/docker-compose/secrets/");
    }

    @Test
    void theServicesAreSwitchedToJsonLogsAndFullTracingOnlyByTheOverlay() throws IOException {
        String overlay = Files.readString(OBS.resolve("docker-compose.observability.yml"));
        assertThat(overlay).contains("SPRING_PROFILES_ACTIVE: json").contains("TRACING_SAMPLING: \"1.0\"").contains("OTEL_TRACES_ENDPOINT");
        // default (no overlay): readable logs and no traces sent, so development and tests are quiet
        for (String service : JAVA_SERVICES) {
            assertThat(Files.readString(BACKEND.resolve(service + "/src/main/resources/application.properties")))
                    .contains("management.tracing.sampling.probability=${TRACING_SAMPLING:0.0}");
        }
        assertThat(Files.readString(Path.of("..", "platform", "src", "main", "resources", "application-json.properties")))
                .contains("logging.structured.format.console=ecs");
    }
}
