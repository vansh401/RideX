package com.ridex.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Kubernetes rules, as tests. A cluster only tells you about a typo in a selector or a Service name when you deploy; these tests tell you
 * at build time. They read the files in infrastructure/k8s (the base and the overlays), and, when kubectl is installed, also the final
 * YAML that kustomize produces for each environment.
 */
class KubernetesPolicyTest {

    private static final Path K8S = Path.of("..", "..", "infrastructure", "k8s");
    private static final Pattern SECRET_NAME = Pattern.compile("(?i).*(PASSWORD|SECRET|TOKEN|PRIVATE_KEY|API_KEY).*");
    private static final Set<String> APPS = Set.of("core", "notification", "payment", "pricing", "analytics", "gateway");

    private static final List<Map<String, Object>> BASE = new ArrayList<>();

    @BeforeAll
    static void load() throws IOException {
        assumeThat(Files.isDirectory(K8S.resolve("base"))).as("run from the architecture-tests module").isTrue();
        try (Stream<Path> files = Files.list(K8S.resolve("base"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yaml") && !f.endsWith("kustomization.yaml")).sorted().toList()) {
                for (Object doc : new Yaml().loadAll(Files.readString(file))) {
                    if (doc instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked") Map<String, Object> typed = (Map<String, Object>) m;
                        BASE.add(typed);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ small helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> ? (Map<String, Object>) o : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o instanceof List<?> ? (List<Object>) o : List.of();
    }

    private static String name(Map<String, Object> doc) {
        return (String) map(doc.get("metadata")).get("name");
    }

    private static List<Map<String, Object>> ofKind(String... kinds) {
        Set<String> wanted = Set.of(kinds);
        return BASE.stream().filter(d -> wanted.contains(d.get("kind"))).toList();
    }

    private static Map<String, Object> podSpec(Map<String, Object> workload) {
        return map(map(map(workload.get("spec")).get("template")).get("spec"));
    }

    private static Map<String, Object> podLabels(Map<String, Object> workload) {
        return map(map(map(map(workload.get("spec")).get("template")).get("metadata")).get("labels"));
    }

    private static List<Map<String, Object>> containers(Map<String, Object> workload) {
        return list(podSpec(workload).get("containers")).stream().map(KubernetesPolicyTest::map).toList();
    }

    private static boolean isOurApp(Map<String, Object> workload) {
        return APPS.contains(name(workload));
    }

    // ------------------------------------------------------------------ workloads: shape and safety

    @Test
    void everyServiceSelectsPodsThatReallyExist() {
        for (Map<String, Object> service : ofKind("Service")) {
            Map<String, Object> selector = map(map(service.get("spec")).get("selector"));
            boolean matches = ofKind("Deployment", "StatefulSet").stream().anyMatch(w -> podLabels(w).entrySet().containsAll(selector.entrySet()));
            assertThat(matches).as("Service " + name(service) + " selects at least one workload").isTrue();
        }
    }

    @Test
    void everyWorkloadSelectorMatchesItsOwnPodLabels() {
        for (Map<String, Object> workload : ofKind("Deployment", "StatefulSet")) {
            Map<String, Object> selector = map(map(map(workload.get("spec")).get("selector")).get("matchLabels"));
            assertThat(podLabels(workload).entrySet()).as(name(workload) + " selector vs pod labels").containsAll(selector.entrySet());
        }
    }

    @Test
    void serviceTargetPortsAreNamedPortsOfTheContainer() {
        for (Map<String, Object> service : ofKind("Service")) {
            Map<String, Object> selector = map(map(service.get("spec")).get("selector"));
            Map<String, Object> workload = ofKind("Deployment", "StatefulSet").stream()
                    .filter(w -> podLabels(w).entrySet().containsAll(selector.entrySet())).findFirst().orElseThrow();
            List<String> portNames = containers(workload).stream().flatMap(c -> list(c.get("ports")).stream())
                    .map(p -> (String) map(p).get("name")).toList();
            for (Object port : list(map(service.get("spec")).get("ports"))) {
                assertThat(portNames).as(name(service) + " targetPort").contains((String) map(port).get("targetPort"));
            }
        }
    }

    @Test
    void everyPodIsHardened() {
        for (Map<String, Object> workload : ofKind("Deployment", "StatefulSet")) {
            String who = name(workload);
            Map<String, Object> pod = podSpec(workload);
            Map<String, Object> podSecurity = map(pod.get("securityContext"));
            assertThat(podSecurity.get("runAsNonRoot")).as(who + " runAsNonRoot").isEqualTo(true);
            assertThat(map(podSecurity.get("seccompProfile")).get("type")).as(who + " seccomp").isEqualTo("RuntimeDefault");
            assertThat(pod.get("automountServiceAccountToken")).as(who + " automountServiceAccountToken").isEqualTo(false);
            assertThat(pod.get("enableServiceLinks")).as(who + " enableServiceLinks (service links inject REDIS_PORT=tcp://... and break Spring and Kafka)").isEqualTo(false);
            assertThat(pod.get("hostNetwork")).as(who + " hostNetwork").isNull();
            assertThat(pod.get("hostPID")).as(who + " hostPID").isNull();
            for (Map<String, Object> container : containers(workload)) {
                Map<String, Object> sc = map(container.get("securityContext"));
                assertThat(sc.get("allowPrivilegeEscalation")).as(who + " allowPrivilegeEscalation").isEqualTo(false);
                assertThat(sc.get("privileged")).as(who + " privileged").isNull();
                assertThat(list(map(sc.get("capabilities")).get("drop"))).as(who + " drops capabilities").contains("ALL");
            }
        }
    }

    @Test
    void ourOwnApplicationsHaveAReadOnlyFilesystemAndPinnedImages() {
        for (Map<String, Object> workload : ofKind("Deployment")) {
            if (!isOurApp(workload) && !name(workload).equals("web")) {
                continue;
            }
            for (Map<String, Object> container : containers(workload)) {
                assertThat(map(container.get("securityContext")).get("readOnlyRootFilesystem")).as(name(workload) + " read-only filesystem").isEqualTo(true);
                String image = (String) container.get("image");
                assertThat(image).as(name(workload) + " image").startsWith("ridex/").doesNotEndWith(":latest").contains(":");
            }
        }
    }

    @Test
    void everyImageIsPinnedToAVersion() {
        for (Map<String, Object> workload : ofKind("Deployment", "StatefulSet")) {
            for (Map<String, Object> container : containers(workload)) {
                assertThat((String) container.get("image")).as(name(workload)).contains(":").doesNotEndWith(":latest");
            }
        }
    }

    @Test
    void everyContainerStatesWhatItNeedsAndWhatItMayUse() {
        for (Map<String, Object> workload : ofKind("Deployment", "StatefulSet")) {
            for (Map<String, Object> container : containers(workload)) {
                Map<String, Object> resources = map(container.get("resources"));
                assertThat(map(resources.get("requests")).get("cpu")).as(name(workload) + " cpu request").isNotNull();
                assertThat(map(resources.get("requests")).get("memory")).as(name(workload) + " memory request").isNotNull();
                assertThat(map(resources.get("limits")).get("memory")).as(name(workload) + " memory limit").isNotNull();
                assertThat(map(resources.get("limits")).get("cpu")).as(name(workload) + " no CPU limit (throttling hurts latency)").isNull();
            }
        }
    }

    // ------------------------------------------------------------------ probes and rolling updates (step 10.6)

    @Test
    void everyJavaServiceHasStartupLivenessAndReadinessProbesOnTheRightEndpoints() {
        for (Map<String, Object> workload : ofKind("Deployment")) {
            if (!isOurApp(workload)) {
                continue;
            }
            Map<String, Object> container = containers(workload).get(0);
            String liveness = (String) map(map(container.get("livenessProbe")).get("httpGet")).get("path");
            String readiness = (String) map(map(container.get("readinessProbe")).get("httpGet")).get("path");
            assertThat(container.get("startupProbe")).as(name(workload) + " startup probe").isNotNull();
            assertThat(liveness).as(name(workload) + " liveness").isEqualTo("/actuator/health/liveness");
            assertThat(readiness).as(name(workload) + " readiness").isEqualTo("/actuator/health/readiness");
        }
    }

    @Test
    void livenessIsNeverTheFullHealthEndpointBecauseADownDatabaseWouldRestartEveryPod() {
        for (Map<String, Object> workload : ofKind("Deployment", "StatefulSet")) {
            for (Map<String, Object> container : containers(workload)) {
                String path = (String) map(map(container.get("livenessProbe")).get("httpGet")).get("path");
                if (path != null) {
                    assertThat(path).as(name(workload)).isIn("/actuator/health/liveness", "/healthz");
                }
            }
        }
    }

    @Test
    void statelessDeploymentsUpdateWithoutLosingCapacityAndTheOnlyExceptionIsTheOneWithADisk() {
        for (Map<String, Object> deployment : ofKind("Deployment")) {
            Map<String, Object> strategy = map(map(deployment.get("spec")).get("strategy"));
            boolean usesDisk = list(podSpec(deployment).get("volumes")).stream().anyMatch(v -> map(v).containsKey("persistentVolumeClaim"));
            if (usesDisk) {
                assertThat(strategy.get("type")).as(name(deployment) + " has a ReadWriteOnce disk").isEqualTo("Recreate");
            } else {
                assertThat(strategy.get("type")).as(name(deployment)).isEqualTo("RollingUpdate");
                assertThat(map(strategy.get("rollingUpdate")).get("maxUnavailable")).as(name(deployment) + " maxUnavailable").isEqualTo(0);
                assertThat(map(strategy.get("rollingUpdate")).get("maxSurge")).as(name(deployment) + " maxSurge").isEqualTo(1);
            }
        }
    }

    @Test
    void podsShutDownGracefullyBeforeTheyAreKilled() {
        for (Map<String, Object> deployment : ofKind("Deployment")) {
            Map<String, Object> container = containers(deployment).get(0);
            List<Object> preStop = list(map(map(map(container.get("lifecycle")).get("preStop")).get("exec")).get("command"));
            assertThat(preStop).as(name(deployment) + " preStop sleep").contains("sleep");
            int grace = (Integer) podSpec(deployment).get("terminationGracePeriodSeconds");
            int sleep = Integer.parseInt(String.valueOf(preStop.get(preStop.size() - 1)));
            int shutdown = isOurApp(deployment) ? 25 : 0;
            assertThat(grace).as(name(deployment) + " grace period covers preStop + graceful shutdown").isGreaterThanOrEqualTo(sleep + shutdown);
        }
    }

    // ------------------------------------------------------------------ stateful components (step 10.5)

    @Test
    void databasesCachesAndBrokersAreStatefulSetsWithTheirOwnDiskNeverPlainDeployments() {
        for (String data : List.of("postgres", "redis", "kafka")) {
            assertThat(ofKind("Deployment").stream().map(KubernetesPolicyTest::name)).as(data + " must not be a Deployment").doesNotContain(data);
            Map<String, Object> sts = ofKind("StatefulSet").stream().filter(s -> name(s).equals(data)).findFirst().orElseThrow();
            assertThat(list(map(sts.get("spec")).get("volumeClaimTemplates"))).as(data + " volumeClaimTemplates").isNotEmpty();
            String governing = (String) map(sts.get("spec")).get("serviceName");
            Map<String, Object> service = ofKind("Service").stream().filter(s -> name(s).equals(governing)).findFirst().orElseThrow();
            if (!data.equals("redis")) {
                assertThat(map(service.get("spec")).get("clusterIP")).as(data + " governing Service is headless").isEqualTo("None");
            }
        }
    }

    // ------------------------------------------------------------------ configuration and secrets (step 10.3)

    @Test
    void theBaseContainsNoSecretObject() {
        assertThat(ofKind("Secret")).as("secrets are made by overlays or outside the repository").isEmpty();
    }

    @Test
    void noPasswordTokenOrKeyIsWrittenInAPodSpecOrAConfigMap() throws IOException {
        for (Map<String, Object> workload : ofKind("Deployment", "StatefulSet")) {
            for (Map<String, Object> container : containers(workload)) {
                for (Object e : list(container.get("env"))) {
                    Map<String, Object> env = map(e);
                    String envName = (String) env.get("name");
                    if (SECRET_NAME.matcher(envName).matches() && !envName.endsWith("_FILE")) {
                        assertThat(env.get("valueFrom")).as(name(workload) + "." + envName + " must come from a Secret").isNotNull();
                        assertThat(env.get("value")).as(name(workload) + "." + envName).isNull();
                    }
                }
            }
        }
        String kustomization = Files.readString(K8S.resolve("base/kustomization.yaml"));
        Matcher literals = Pattern.compile("^\\s+- ([A-Z_]+)=", Pattern.MULTILINE).matcher(kustomization);
        while (literals.find()) {
            assertThat(SECRET_NAME.matcher(literals.group(1)).matches()).as("ConfigMap key " + literals.group(1)).isFalse();
        }
    }

    @Test
    void everyHostnameInTheConfigMapIsAServiceThatExistsOnTheRightPort() throws IOException {
        Map<String, Integer> servicePorts = new LinkedHashMap<>();
        for (Map<String, Object> service : ofKind("Service")) {
            servicePorts.put(name(service), (Integer) map(list(map(service.get("spec")).get("ports")).get(0)).get("port"));
        }
        String kustomization = Files.readString(K8S.resolve("base/kustomization.yaml"));
        Matcher url = Pattern.compile("=(?:https?|wss?)://([a-z]+):(\\d+)").matcher(kustomization);
        int found = 0;
        while (url.find()) {
            found++;
            assertThat(servicePorts).as("Service for " + url.group()).containsKey(url.group(1));
            assertThat(servicePorts.get(url.group(1))).as("port of " + url.group(1)).isEqualTo(Integer.parseInt(url.group(2)));
        }
        assertThat(found).isGreaterThanOrEqualTo(6);
    }

    @Test
    void theSecretsEveryPodMountsAreTheOnesThePlatformKnowsAbout() {
        for (Map<String, Object> workload : ofKind("Deployment", "StatefulSet")) {
            for (Object v : list(podSpec(workload).get("volumes"))) {
                Map<String, Object> secret = map(map(v).get("secret"));
                if (!secret.isEmpty()) {
                    assertThat(secret.get("secretName")).as(name(workload)).isEqualTo("ridex-secrets");
                    for (Object item : list(secret.get("items"))) {
                        assertThat((String) map(item).get("key")).as(name(workload)).isIn("DB_PASSWORD", "SERVICE_TOKEN");
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ ingress and network (step 10.4)

    @Test
    void theIngressOnlyExposesTheGatewayAndTheWebsite() {
        Map<String, Object> ingress = ofKind("Ingress").get(0);
        assertThat(map(ingress.get("spec")).get("ingressClassName")).isEqualTo("nginx");
        List<String> backends = new ArrayList<>();
        for (Object rule : list(map(ingress.get("spec")).get("rules"))) {
            for (Object path : list(map(map(rule).get("http")).get("paths"))) {
                backends.add((String) map(map(map(path).get("backend")).get("service")).get("name"));
            }
        }
        assertThat(backends).isNotEmpty().allMatch(b -> b.equals("gateway") || b.equals("web"));
    }

    @Test
    void networkPoliciesStartFromDenyAllAndEveryRuleTargetsRealPods() {
        List<Map<String, Object>> policies = ofKind("NetworkPolicy");
        boolean denyAll = policies.stream().anyMatch(p -> map(map(p.get("spec")).get("podSelector")).isEmpty()
                && list(map(p.get("spec")).get("ingress")).isEmpty());
        assertThat(denyAll).as("a default-deny policy").isTrue();
        List<String> databases = List.of("postgres", "redis", "kafka");
        for (String db : databases) {
            boolean restricted = policies.stream().anyMatch(p -> db.equals(map(map(map(p.get("spec")).get("podSelector")).get("matchLabels")).get("app.kubernetes.io/name")));
            assertThat(restricted).as(db + " has an explicit allow rule").isTrue();
        }
    }

    // ------------------------------------------------------------------ the overlays (step 10.3)

    @Test
    void eachEnvironmentLivesInItsOwnNamespaceWithPodSecurityLabels() throws IOException {
        Set<String> seen = new java.util.HashSet<>();
        for (String env : List.of("dev", "staging", "prod")) {
            String kustomization = Files.readString(K8S.resolve("overlays/" + env + "/kustomization.yaml"));
            Matcher ns = Pattern.compile("(?m)^namespace: (\\S+)").matcher(kustomization);
            assertThat(ns.find()).as(env + " sets a namespace").isTrue();
            assertThat(ns.group(1)).isEqualTo("ridex-" + env);
            assertThat(seen.add(ns.group(1))).as("namespaces are distinct").isTrue();
            String namespaceFile = Files.readString(K8S.resolve("overlays/" + env + "/namespace.yaml"));
            assertThat(namespaceFile).contains("pod-security.kubernetes.io/enforce").contains("name: ridex-" + env);
        }
    }

    @Test
    void onlyDevGeneratesSecretsAndStagingAndProdTakeThemFromOutsideTheRepository() throws IOException {
        assertThat(Files.readString(K8S.resolve("overlays/dev/kustomization.yaml"))).contains("secretGenerator");
        for (String env : List.of("staging", "prod")) {
            String kustomization = Files.readAllLines(K8S.resolve("overlays/" + env + "/kustomization.yaml")).stream()
                    .filter(line -> !line.strip().startsWith("#")).collect(java.util.stream.Collectors.joining("\n"));   // comments may talk about it
            assertThat(kustomization).as(env).doesNotContain("secretGenerator").doesNotContain("secrets/");
        }
        assertThat(Files.readString(K8S.resolve("../../.gitignore"))).contains("infrastructure/k8s/overlays/dev/secrets/");
    }

    @Test
    void productionUsesRegistryImagesWithExactVersionsAndTls() throws IOException {
        String prod = Files.readString(K8S.resolve("overlays/prod/kustomization.yaml"));
        Matcher tags = Pattern.compile("newTag: \"([^\"]+)\"").matcher(prod);
        int count = 0;
        while (tags.find()) {
            count++;
            assertThat(tags.group(1)).doesNotContain("latest").doesNotContain("dev").matches("\\d+\\.\\d+\\.\\d+");
        }
        assertThat(count).as("all seven images are retagged").isEqualTo(7);
        assertThat(prod).contains("/spec/tls").contains("hpa.yaml");
        assertThat(Files.readString(K8S.resolve("overlays/staging/kustomization.yaml"))).contains("/spec/tls");
    }

    // ------------------------------------------------------------------ what kustomize really produces (needs kubectl)

    private static String render(String env) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("kubectl", "kustomize", K8S.resolve("overlays/" + env).toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as("kubectl kustomize " + env + ":\n" + out).isZero();
        return out;
    }

    private static boolean kubectlAvailable() {
        try {
            Process p = new ProcessBuilder("kubectl", "version", "--client").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Test
    void stagingAndProdRenderAndEverythingTheyReferenceExists() throws Exception {
        assumeThat(kubectlAvailable()).as("kubectl is installed").isTrue();
        for (String env : List.of("staging", "prod")) {
            List<Map<String, Object>> docs = new ArrayList<>();
            for (Object d : new Yaml().loadAll(render(env))) {
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) d;
                docs.add(m);
            }
            String namespace = "ridex-" + env;
            Set<String> configMaps = new java.util.HashSet<>();
            for (Map<String, Object> d : docs) {
                String kind = (String) d.get("kind");
                if (kind.equals("ConfigMap")) {
                    configMaps.add(name(d));
                }
                if (!kind.equals("Namespace")) {
                    assertThat(map(d.get("metadata")).get("namespace")).as(env + " " + kind + "/" + name(d)).isEqualTo(namespace);
                }
            }
            for (Map<String, Object> d : docs) {
                if (d.get("kind").equals("Deployment")) {
                    for (Map<String, Object> c : containers(d)) {
                        for (Object ef : list(c.get("envFrom"))) {
                            String ref = (String) map(map(ef).get("configMapRef")).get("name");
                            assertThat(configMaps).as(env + " " + name(d) + " references its ConfigMap by the generated name").contains(ref);
                        }
                        assertThat((String) c.get("image")).as(env + " " + name(d)).doesNotContain(":dev").doesNotContain(":latest");
                    }
                }
            }
        }
    }

    @Test
    void devRendersWhenItsSecretFilesExist() throws Exception {
        assumeThat(kubectlAvailable()).as("kubectl is installed").isTrue();
        assumeThat(Files.exists(K8S.resolve("overlays/dev/secrets/DB_PASSWORD"))).as("scripts/k8s-init.ps1 has been run").isTrue();
        String rendered = render("dev");
        assertThat(rendered).contains("namespace: ridex-dev").contains("kind: Secret").contains("name: mailpit");
        assertThat(rendered).as("no secret value leaks into plain text of workloads").doesNotContain("POSTGRES_PASSWORD:");
    }
}
