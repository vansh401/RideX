package com.ridex.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Container rules, as tests. A Dockerfile or compose file that runs as root, keeps a password in plain text or publishes a database to
 * the whole network "works" just as well as a safe one, so only a failing build keeps it from sneaking in.
 *
 * It reads the real files: backend/Dockerfile, frontend/ridex-web/Dockerfile and infrastructure/docker-compose/docker-compose.yml.
 */
class ContainerPolicyTest {

    private static final Path REPO = Path.of("..", "..");
    private static final List<String> JAVA_SERVICES = List.of("core", "notification", "payment", "pricing", "analytics", "gateway");
    private static final Pattern SECRET_NAME = Pattern.compile("(?i).*(PASSWORD|SECRET|TOKEN|PRIVATE_KEY|API_KEY).*");

    private static String backendDockerfile;
    private static String webDockerfile;
    private static Map<String, Object> compose;

    @SuppressWarnings("unchecked")
    @BeforeAll
    static void load() throws IOException {
        Path backend = REPO.resolve("backend/Dockerfile");
        assumeThat(Files.exists(backend)).as("run from the architecture-tests module").isTrue();
        backendDockerfile = Files.readString(backend);
        webDockerfile = Files.readString(REPO.resolve("frontend/ridex-web/Dockerfile"));
        compose = new Yaml().load(Files.readString(REPO.resolve("infrastructure/docker-compose/docker-compose.yml")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> services() {
        return (Map<String, Map<String, Object>>) compose.get("services");
    }

    /** The instruction lines of a Dockerfile without comments, with line continuations joined. */
    private static List<String> instructions(String dockerfile) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String raw : dockerfile.split("\n")) {
            String line = raw.strip();
            if (line.startsWith("#") || line.isEmpty()) {
                continue;
            }
            if (line.endsWith("\\")) {
                current.append(line, 0, line.length() - 1).append(' ');
            } else {
                current.append(line);
                result.add(current.toString());
                current.setLength(0);
            }
        }
        return result;
    }

    private static List<String> fromLines(String dockerfile) {
        return instructions(dockerfile).stream().filter(l -> l.toUpperCase().startsWith("FROM ")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> asList(Object value) {
        return value == null ? List.of() : (List<String>) value;
    }

    // ------------------------------------------------------------------ Dockerfiles

    @Test
    void bothDockerfilesAreMultiStageSoBuildToolsNeverShipInTheImage() {
        assertThat(fromLines(backendDockerfile)).as("backend stages").hasSizeGreaterThanOrEqualTo(3);
        assertThat(fromLines(webDockerfile)).as("web stages").hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void everyBaseImageIsPinnedToAVersionNeverLatest() {
        for (String line : List.of(fromLines(backendDockerfile), fromLines(webDockerfile)).stream().flatMap(List::stream).toList()) {
            String image = line.split("\\s+")[1];
            if (image.equals("build") || image.equals("layers")) {
                continue;   // an earlier stage of the same file
            }
            assertThat(image).as(line).contains(":").doesNotEndWith(":latest");
        }
    }

    @Test
    void theBackendImageRunsAsANonRootUser() {
        List<String> all = instructions(backendDockerfile);
        int runtimeStart = 0;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).toUpperCase().startsWith("FROM ") && all.get(i).contains(" AS runtime")) {
                runtimeStart = i;
            }
        }
        List<String> runtime = all.subList(runtimeStart, all.size());
        List<String> users = runtime.stream().filter(l -> l.toUpperCase().startsWith("USER ")).toList();

        assertThat(users).as("the final stage must switch to a user").isNotEmpty();
        assertThat(users.get(users.size() - 1)).doesNotContain("root").doesNotContain(" 0");
    }

    @Test
    void theWebImageRunsAsANonRootUserBecauseItIsBasedOnTheUnprivilegedNginx() {
        List<String> from = fromLines(webDockerfile);
        assertThat(from.get(from.size() - 1)).contains("nginx-unprivileged");
    }

    @Test
    void theRuntimeStageHasNoBuildTools() {
        String runtime = backendDockerfile.substring(backendDockerfile.indexOf("AS runtime"));
        assertThat(runtime).doesNotContain("mvn ").doesNotContain("apk add").doesNotContain("curl");
        assertThat(fromLines(backendDockerfile).get(fromLines(backendDockerfile).size() - 1)).contains("jre");
    }

    @Test
    void noDockerfileBakesASecretIntoAnImage() {
        for (String dockerfile : List.of(backendDockerfile, webDockerfile)) {
            for (String line : instructions(dockerfile)) {
                String upper = line.toUpperCase();
                if (upper.startsWith("ENV ") || upper.startsWith("ARG ")) {
                    String name = line.split("[\\s=]+")[1];
                    assertThat(SECRET_NAME.matcher(name).matches()).as("secret-looking variable in image: " + line).isFalse();
                }
                assertThat(line).as("copying secrets into an image").doesNotContain(".env").doesNotContain(".pem").doesNotContain("secrets/");
            }
        }
    }

    @Test
    void theBuildContextsExcludeSecretsAndBuildOutput() throws IOException {
        String backend = Files.readString(REPO.resolve("backend/.dockerignore"));
        assertThat(backend).contains("**/target/").contains("**/.env").contains("**/*.pem").contains("**/data/");
        String web = Files.readString(REPO.resolve("frontend/ridex-web/.dockerignore"));
        assertThat(web).contains("node_modules/").contains(".env");
    }

    // ------------------------------------------------------------------ compose: images and hardening

    @Test
    void everyImageIsPinned() {
        services().forEach((name, service) -> {
            String image = (String) service.get("image");
            assertThat(image).as(name + " image").isNotNull().contains(":").doesNotEndWith(":latest");
        });
    }

    @Test
    void everyApplicationContainerIsHardenedAndLimited() {
        List<String> ours = new ArrayList<>(JAVA_SERVICES);
        ours.add("web");
        for (String name : ours) {
            Map<String, Object> s = services().get(name);
            assertThat(s).as(name).isNotNull();
            assertThat(s.get("read_only")).as(name + " read_only").isEqualTo(true);
            assertThat(asList(s.get("cap_drop"))).as(name + " cap_drop").contains("ALL");
            assertThat(asList(s.get("security_opt"))).as(name + " security_opt").contains("no-new-privileges:true");
            assertThat(s.get("mem_limit")).as(name + " memory limit").isNotNull();
            assertThat(s.get("restart")).as(name + " restart policy").isNotNull();
            assertThat(s.get("init")).as(name + " init").isEqualTo(true);
            assertThat(s.get("healthcheck")).as(name + " health check").isNotNull();
            assertThat(s.get("logging")).as(name + " log rotation").isNotNull();
        }
    }

    @Test
    void everyInfrastructureContainerHasAHealthCheckBecauseOtherContainersWaitForIt() {
        for (String name : List.of("postgres", "redis", "kafka", "mailpit")) {
            if (!name.equals("mailpit")) {
                assertThat(services().get(name).get("healthcheck")).as(name + " health check").isNotNull();
            }
            assertThat(services().get(name).get("logging")).as(name + " log rotation").isNotNull();
        }
    }

    // ------------------------------------------------------------------ compose: startup order and networks

    @SuppressWarnings("unchecked")
    @Test
    void dependenciesWaitUntilTheyAreHealthyNotJustStarted() {
        services().forEach((name, service) -> {
            Map<String, Map<String, String>> dependsOn = (Map<String, Map<String, String>>) service.get("depends_on");
            if (dependsOn != null) {
                dependsOn.forEach((dependency, settings) ->
                        assertThat(settings.get("condition")).as(name + " -> " + dependency).isEqualTo("service_healthy"));
            }
        });
    }

    @Test
    void theDatabaseCacheAndBrokerAreNeverOnTheNetworkTheWebsiteSees() {
        for (String name : List.of("postgres", "redis", "kafka", "core", "notification", "payment", "pricing", "analytics")) {
            assertThat(asList(services().get(name).get("networks"))).as(name).containsExactly("internal");
        }
        assertThat(asList(services().get("web").get("networks"))).containsExactly("edge");
        assertThat(asList(services().get("gateway").get("networks"))).containsExactlyInAnyOrder("edge", "internal");
    }

    // ------------------------------------------------------------------ compose: ports

    @Test
    void everyPublishedPortIsBoundToLocalhostOnly() {
        services().forEach((name, service) -> {
            for (Object port : asList(service.get("ports"))) {
                assertThat(port.toString()).as(name + " published port").startsWith("127.0.0.1:");
            }
        });
    }

    @Test
    void theJavaServicesAreNotPublishedExceptTheGatewayForLocalTools() {
        for (String name : List.of("core", "notification", "payment", "pricing", "analytics")) {
            assertThat(services().get(name).get("ports")).as(name + " must stay internal").isNull();
        }
    }

    // ------------------------------------------------------------------ compose: secrets

    @SuppressWarnings("unchecked")
    @Test
    void noSecretIsWrittenInTheComposeFile() {
        services().forEach((name, service) -> {
            Object environment = service.get("environment");
            if (environment instanceof Map<?, ?> map) {
                ((Map<String, Object>) map).forEach((key, value) -> {
                    if (SECRET_NAME.matcher(key).matches()) {
                        // allowed only as a reference to the shell / .env file, e.g. ${ADMIN_PASSWORD:-}
                        assertThat(String.valueOf(value)).as(name + "." + key).startsWith("${");
                    }
                });
            }
        });
    }

    @Test
    void servicesThatNeedASecretGetItAsAFileAndOnlyThoseServices() {
        for (String name : List.of("core", "notification", "payment", "analytics")) {
            assertThat(asList(services().get(name).get("secrets"))).as(name).contains("DB_PASSWORD");
        }
        for (String name : List.of("core", "pricing")) {
            assertThat(asList(services().get(name).get("secrets"))).as(name).contains("SERVICE_TOKEN");
        }
        for (String name : List.of("gateway", "web")) {
            assertThat(services().get(name).get("secrets")).as(name + " needs no secrets").isNull();
        }
    }

    @Test
    void theSecretFilesAreGitIgnored() throws IOException {
        assertThat(Files.readString(REPO.resolve(".gitignore"))).contains("infrastructure/docker-compose/secrets/");
        Matcher files = Pattern.compile("file:\\s*\\./(secrets/\\w+)").matcher(Files.readString(REPO.resolve("infrastructure/docker-compose/docker-compose.yml")));
        assertThat(files.find()).as("secrets are declared as files under ./secrets").isTrue();
    }
}
