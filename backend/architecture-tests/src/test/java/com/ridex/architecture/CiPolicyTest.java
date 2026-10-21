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
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Rules for the delivery pipeline, as tests. A pipeline is code with the keys to production, and its mistakes are quiet: a job that
 * can write to the registry from a pull request, a secret printed in a log, a deployment that skips the approval. These tests read
 * the workflow files and fail the build on the dangerous patterns. (actionlint, run by CI itself, checks the syntax; this checks the policy.)
 */
class CiPolicyTest {

    private static final Path ROOT = Path.of("..", "..");
    private static final Path WORKFLOWS = ROOT.resolve(".github/workflows");

    private static final Map<String, Map<String, Object>> DOCS = new java.util.TreeMap<>();
    private static final Map<String, String> TEXT = new java.util.TreeMap<>();

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws IOException {
        assumeThat(Files.isDirectory(WORKFLOWS)).as("run from the architecture-tests module").isTrue();
        try (Stream<Path> files = Files.list(WORKFLOWS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yml")).toList()) {
                String text = Files.readString(file);
                TEXT.put(file.getFileName().toString(), text);
                DOCS.put(file.getFileName().toString(), (Map<String, Object>) new Yaml().load(text));
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> ? (Map<String, Object>) o : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o instanceof List<?> ? (List<Object>) o : List.of();
    }

    /** "needs: images" and "needs: [images]" mean the same. */
    private static List<Object> needs(Object job) {
        Object needs = map(job).get("needs");
        return needs instanceof List<?> ? list(needs) : needs == null ? List.of() : List.of(needs);
    }

    /** YAML 1.1 reads the key "on" as the boolean true. */
    private static Map<String, Object> triggers(String workflow) {
        Map<String, Object> doc = DOCS.get(workflow);
        Object on = doc.containsKey("on") ? doc.get("on") : doc.get(Boolean.TRUE);
        return map(on);
    }

    private static Map<String, Object> jobs(String workflow) {
        return map(DOCS.get(workflow).get("jobs"));
    }

    private static List<Map<String, Object>> steps(Map<String, Object> job) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Object s : list(job.get("steps"))) {
            steps.add(map(s));
        }
        return steps;
    }

    // ------------------------------------------------------------------ least privilege

    @Test
    void theWorkflowsExistAndTheDefaultTokenCanOnlyReadTheRepository() {
        assertThat(DOCS.keySet()).contains("ci.yml", "cd.yml");
        for (String workflow : DOCS.keySet()) {
            Map<String, Object> permissions = map(DOCS.get(workflow).get("permissions"));
            assertThat(permissions).as(workflow + " declares its permissions at the top").isNotEmpty();
            assertThat(permissions).as(workflow + ": the default is read-only").containsOnly(Map.entry("contents", "read"));
        }
    }

    @Test
    void onlyTheImageJobOfTheDeliveryWorkflowMayWriteToTheRegistry() {
        for (String workflow : DOCS.keySet()) {
            for (Map.Entry<String, Object> job : jobs(workflow).entrySet()) {
                Map<String, Object> permissions = map(map(job.getValue()).get("permissions"));
                if (permissions.containsKey("packages")) {
                    assertThat(workflow + "/" + job.getKey()).as("the only job with packages: write").isEqualTo("cd.yml/images");
                    assertThat(permissions.get("packages")).isEqualTo("write");
                }
            }
        }
        assertThat(TEXT.get("ci.yml")).as("CI never pushes an image (it runs on pull requests, which may come from forks)")
                .contains("push: false").doesNotContain("push: true");
    }

    @Test
    void noWorkflowRunsWithTheSecretsOfTheBaseRepositoryOnCodeFromAPullRequest() {
        for (Map.Entry<String, String> workflow : TEXT.entrySet()) {
            assertThat(workflow.getValue()).as(workflow.getKey() + " must not use pull_request_target (it exposes secrets to forks)")
                    .doesNotContain("pull_request_target");
        }
    }

    // ------------------------------------------------------------------ secrets

    @Test
    void secretsReachScriptsOnlyThroughEnvironmentVariablesNeverInsideACommand() {
        Pattern secretInRun = Pattern.compile("\\$\\{\\{\\s*secrets\\.");
        for (String workflow : DOCS.keySet()) {
            for (Map.Entry<String, Object> job : jobs(workflow).entrySet()) {
                for (Map<String, Object> step : steps(map(job.getValue()))) {
                    String run = (String) step.get("run");
                    if (run != null) {
                        assertThat(secretInRun.matcher(run).find())
                                .as(workflow + "/" + job.getKey() + ": a secret written inside a shell command can leak through the process list or a log; use env:")
                                .isFalse();
                    }
                }
            }
        }
    }

    @Test
    void noWorkflowEchoesAnEnvironmentSecretOrDumpsTheEnvironment() {
        for (Map.Entry<String, String> workflow : TEXT.entrySet()) {
            assertThat(workflow.getValue()).as(workflow.getKey())
                    .doesNotContainPattern("(?m)^\\s*(echo|printf)\\s+[\"']?\\$\\{?(DB_PASSWORD|SERVICE_TOKEN|PULL_TOKEN)")
                    .doesNotContainPattern("(?m)^\\s*(env|printenv|set -x)\\s*$");
        }
    }

    // ------------------------------------------------------------------ supply chain

    @Test
    void everyActionIsPinnedToAVersionNeverToAMovingBranch() {
        Pattern pinned = Pattern.compile("(.+@(v\\d+(\\.\\d+)*|[0-9a-f]{40}))|docker://\\S+:\\d+(\\.\\d+)*|\\./.+");
        for (String workflow : DOCS.keySet()) {
            for (Map.Entry<String, Object> job : jobs(workflow).entrySet()) {
                Map<String, Object> body = map(job.getValue());
                List<String> uses = new ArrayList<>();
                if (body.get("uses") != null) {
                    uses.add((String) body.get("uses"));
                }
                steps(body).forEach(s -> {
                    if (s.get("uses") != null) {
                        uses.add((String) s.get("uses"));
                    }
                });
                for (String action : uses) {
                    assertThat(action).as(workflow + "/" + job.getKey() + ": " + action + " must be pinned to a version (Dependabot keeps it current)")
                            .matches(pinned);
                }
            }
        }
    }

    @Test
    void dependabotWatchesEveryKindOfDependencyTheProjectHas() throws IOException {
        String dependabot = Files.readString(ROOT.resolve(".github/dependabot.yml"));
        for (String ecosystem : List.of("maven", "npm", "github-actions", "docker")) {
            assertThat(dependabot).contains("package-ecosystem: " + ecosystem);
        }
    }

    @Test
    void noWorkflowDeploysOrBuildsALatestImage() {
        for (Map.Entry<String, String> workflow : TEXT.entrySet()) {
            assertThat(workflow.getValue()).as(workflow.getKey()).doesNotContainPattern("(?m)^[^#\\n]*:latest\\b");
        }
    }

    // ------------------------------------------------------------------ shape of the pipeline

    @Test
    void everyJobThatRunsOnAMachineHasATimeoutSoAHungJobCannotBurnHours() {
        for (String workflow : DOCS.keySet()) {
            for (Map.Entry<String, Object> job : jobs(workflow).entrySet()) {
                Map<String, Object> body = map(job.getValue());
                if (body.containsKey("uses")) {
                    continue;   // a job that only calls another workflow cannot have one: its jobs do
                }
                assertThat(body).as(workflow + "/" + job.getKey()).containsKey("timeout-minutes");
                assertThat((Integer) body.get("timeout-minutes")).isLessThanOrEqualTo(60);
            }
        }
    }

    @Test
    void ciRunsOnPullRequestsAndOnMainAndCanBeCalledByTheDeliveryWorkflow() {
        Map<String, Object> on = triggers("ci.yml");
        assertThat(on).containsKeys("pull_request", "push", "workflow_call");
        assertThat(jobs("ci.yml")).containsKeys("backend-unit", "backend-integration", "frontend", "docker-build", "manifests", "workflows", "ci-ok");
    }

    @Test
    void theIntegrationTestsRunInTheirOwnJobAndTheUnitTestsExcludeThem() {
        assertThat(TEXT.get("ci.yml")).contains("-DexcludedGroups=integration").contains("-Dgroups=integration");
    }

    @Test
    void theSingleRequiredCheckLooksAtEveryOtherJobAndAlsoWhenTheyFail() {
        Map<String, Object> ciOk = map(jobs("ci.yml").get("ci-ok"));
        assertThat((String) ciOk.get("if")).contains("always()");
        List<Object> needs = list(ciOk.get("needs"));
        List<String> others = new ArrayList<>(jobs("ci.yml").keySet());
        others.remove("ci-ok");
        assertThat(needs).as("ci-ok depends on all other jobs").containsExactlyInAnyOrderElementsOf(others);
    }

    // ------------------------------------------------------------------ delivery

    @Test
    void theDeliveryRunsOnlyOnMainAndOnVersionTags() {
        Map<String, Object> on = triggers("cd.yml");
        assertThat(on).containsOnlyKeys("push");
        assertThat(map(on.get("push")).get("branches")).isEqualTo(List.of("main"));
        assertThat(list(map(on.get("push")).get("tags"))).hasSize(1);
        assertThat((String) list(map(on.get("push")).get("tags")).get(0)).startsWith("v");
    }

    @Test
    void nothingIsBuiltOrDeployedBeforeTheSameChecksAsAPullRequestPassed() {
        Map<String, Object> cdJobs = jobs("cd.yml");
        assertThat(map(cdJobs.get("ci")).get("uses")).isEqualTo("./.github/workflows/ci.yml");
        assertThat(needs(cdJobs.get("images"))).isEqualTo(List.of("ci"));
        assertThat(needs(cdJobs.get("deploy-staging"))).isEqualTo(List.of("images"));
        assertThat(needs(cdJobs.get("deploy-production"))).isEqualTo(List.of("images"));
    }

    @Test
    void stagingDeploysAutomaticallyFromMainAndProductionOnlyFromAReleaseTagThroughItsEnvironment() {
        Map<String, Object> cdJobs = jobs("cd.yml");
        Map<String, Object> staging = map(cdJobs.get("deploy-staging"));
        Map<String, Object> production = map(cdJobs.get("deploy-production"));
        assertThat(map(staging.get("environment")).get("name")).isEqualTo("staging");
        assertThat((String) staging.get("if")).contains("refs/heads/main");
        assertThat(map(production.get("environment")).get("name")).as("the environment is where the approval is configured").isEqualTo("production");
        assertThat((String) production.get("if")).contains("refs/tags/v");
        // a different environment can never be reached by mistake: each job uses its own overlay
        assertThat(TEXT.get("cd.yml")).contains("deploy.sh staging").contains("deploy.sh prod ");
    }

    @Test
    void aDeploymentNeverCancelsAnotherOneHalfway() {
        Map<String, Object> concurrency = map(DOCS.get("cd.yml").get("concurrency"));
        assertThat(concurrency.get("cancel-in-progress")).isEqualTo(false);
    }

    @Test
    void imagesAreBuiltOnceAndReleasesOnlyAddAnotherNameToThem() {
        String cd = TEXT.get("cd.yml");
        assertThat(cd).contains("sha-").contains("scripts/ci/promote.sh").contains("scripts/ci/image-exists.sh");
        assertThat(cd).as("a release must come from main").contains("merge-base --is-ancestor");
        assertThat(cd).as("the pipeline scans what it publishes").contains("trivy");
    }

    @Test
    void everyScriptTheWorkflowsCallExistsAndStartsWithStrictMode() throws IOException {
        Matcher m = Pattern.compile("scripts/ci/([a-z-]+\\.sh)").matcher(TEXT.get("cd.yml") + TEXT.get("ci.yml"));
        int found = 0;
        while (m.find()) {
            found++;
            Path script = ROOT.resolve("scripts/ci/" + m.group(1));
            assertThat(script).exists();
            assertThat(Files.readString(script)).startsWith("#!/usr/bin/env bash").contains("set -");
        }
        assertThat(found).isGreaterThan(3);
    }

    @Test
    void theScriptsKeepUnixLineEndingsBecauseTheyRunOnLinux() throws IOException {
        assertThat(Files.readString(ROOT.resolve(".gitattributes"))).contains("*.sh").contains("eol=lf");
        try (Stream<Path> scripts = Files.list(ROOT.resolve("scripts/ci"))) {
            for (Path script : scripts.filter(p -> p.toString().endsWith(".sh")).toList()) {
                assertThat(Files.readString(script)).as(script + " has no Windows line endings").doesNotContain("\r\n");
            }
        }
    }

    @Test
    void deploymentScriptsRefusePlaceholderAndLocalImagesAndKeepSecretsOffCommandLines() throws IOException {
        String deploy = Files.readString(ROOT.resolve("scripts/ci/deploy.sh"));
        assertThat(deploy).contains("example-org").contains("rollout undo").contains("--from-file");
        assertThat(deploy).as("secret values are never passed as arguments").doesNotContain("--from-literal");
        String render = Files.readString(ROOT.resolve("scripts/ci/render.sh"));
        assertThat(render).contains("sha-[0-9a-f]").contains("refusing image tag");
    }

    // ------------------------------------------------------------------ the pipeline's identity in the cluster

    @Test
    @SuppressWarnings("unchecked")
    void theDeployerCanOnlyActInOneNamespaceAndCannotDeleteExecReadAllSecretsOrChangePermissions() throws IOException {
        List<Map<String, Object>> docs = new ArrayList<>();
        for (Object d : new Yaml().loadAll(Files.readString(ROOT.resolve("infrastructure/k8s/ci/deployer.yaml")))) {
            docs.add((Map<String, Object>) d);
        }
        assertThat(docs.stream().map(d -> (String) d.get("kind")).toList())
                .as("only namespaced objects: no ClusterRole, no ClusterRoleBinding").containsExactly("ServiceAccount", "Role", "RoleBinding");
        Map<String, Object> role = docs.get(1);
        for (Object r : (List<Object>) role.get("rules")) {
            Map<String, Object> rule = (Map<String, Object>) r;
            List<String> resources = (List<String>) rule.get("resources");
            List<String> verbs = (List<String>) rule.get("verbs");
            assertThat(verbs).as(resources + ": no wildcard and no delete").doesNotContain("*", "delete", "deletecollection", "impersonate", "escalate", "bind");
            assertThat(resources).as("no wildcard, no exec, no logs").doesNotContain("*", "pods/exec", "pods/log", "pods/attach", "roles", "rolebindings");
            if (resources.contains("secrets")) {
                assertThat(verbs).as("secrets can be written but not listed").doesNotContain("list", "watch");
            }
        }
    }

    @Test
    void theDeploymentScriptAsksAboutTheNamespaceInAWayANamespacedCredentialIsAllowedTo() throws IOException {
        String deploy = Files.readString(ROOT.resolve("scripts/ci/deploy.sh"));
        assertThat(deploy).contains("get serviceaccount default").doesNotContain("get namespace ");
        assertThat(deploy).as("the Namespace object is not applied (it is cluster-scoped)").contains("kind: Namespace");
    }
}
