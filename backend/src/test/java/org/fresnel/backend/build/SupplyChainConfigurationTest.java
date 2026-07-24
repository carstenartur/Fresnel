package org.fresnel.backend.build;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SupplyChainConfigurationTest {

    private static final Pattern USES_PATTERN =
            Pattern.compile("^\\s*(?:-\\s*)?uses:\\s*([^\\s#]+)");
    private static final Pattern ACTION_SHA_PATTERN =
            Pattern.compile("^[^@\\s]+@[0-9a-f]{40}$");
    private static final Pattern DOCKER_ACTION_DIGEST_PATTERN =
            Pattern.compile("^docker://[^@\\s]+@sha256:[0-9a-f]{64}$");
    private static final Pattern FROM_PATTERN =
            Pattern.compile("^\\s*FROM\\s+([^\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_DIGEST_PATTERN =
            Pattern.compile("^[^@\\s]+@sha256:[0-9a-f]{64}$");

    private static final Path ROOT = findRepositoryRoot();
    private static final Path WORKFLOWS = ROOT.resolve(".github/workflows");

    @Test
    void immutableSupplyChainAndReleaseInvariantsArePartOfMavenVerify() throws IOException {
        List<String> errors = new ArrayList<>();

        checkWorkflowActions(errors);
        checkDockerfileBases(errors);
        checkReleaseInvariants(errors);

        assertThat(errors)
                .as("Supply-chain configuration must be reproducible with 'mvn verify'")
                .isEmpty();
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve(".github/workflows"))
                    && Files.isRegularFile(current.resolve("Dockerfile"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate the Fresnel repository root");
    }

    private static void checkWorkflowActions(List<String> errors) throws IOException {
        List<Path> workflowPaths;
        try (Stream<Path> paths = Files.list(WORKFLOWS)) {
            workflowPaths = paths
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .sorted()
                    .toList();
        }

        if (workflowPaths.isEmpty()) {
            errors.add("No GitHub Actions workflows found");
            return;
        }

        for (Path workflow : workflowPaths) {
            List<String> lines = Files.readAllLines(workflow, UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                Matcher matcher = USES_PATTERN.matcher(lines.get(index));
                if (!matcher.find()) {
                    continue;
                }

                String reference = stripQuotes(matcher.group(1));
                if (reference.startsWith("./")) {
                    continue;
                }
                if (reference.startsWith("docker://")) {
                    if (!DOCKER_ACTION_DIGEST_PATTERN.matcher(reference).matches()) {
                        errors.add(location(workflow, index + 1)
                                + ": Docker action is not pinned by sha256 digest: "
                                + reference);
                    }
                    continue;
                }
                if (!ACTION_SHA_PATTERN.matcher(reference).matches()) {
                    errors.add(location(workflow, index + 1)
                            + ": action is not pinned to a full commit SHA: "
                            + reference);
                }
            }
        }
    }

    private static void checkDockerfileBases(List<String> errors) throws IOException {
        Path dockerfile = ROOT.resolve("Dockerfile");
        if (!Files.isRegularFile(dockerfile)) {
            errors.add("Dockerfile is missing");
            return;
        }

        List<String> lines = Files.readAllLines(dockerfile, UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            Matcher matcher = FROM_PATTERN.matcher(lines.get(index));
            if (!matcher.find()) {
                continue;
            }
            String image = matcher.group(1);
            if (!image.equalsIgnoreCase("scratch")
                    && !IMAGE_DIGEST_PATTERN.matcher(image).matches()) {
                errors.add("Dockerfile:" + (index + 1)
                        + ": base image is not pinned by sha256 digest: "
                        + image);
            }
        }
    }

    private static void checkReleaseInvariants(List<String> errors) throws IOException {
        Path orchestrator = WORKFLOWS.resolve("deploy-release.yml");
        String orchestratorContent = requireTokens(orchestrator, orderedMap(
                "group: fresnel-release-orchestration", "serialized release preparation",
                "refs/heads/main", "main-only manual dispatch",
                "publish-release.yml", "separate immutable-candidate publication",
                "Build and verify candidate with tests", "candidate test gate",
                "gh run watch", "publication result propagation",
                "release/candidate-", "isolated candidate branch"), errors);
        rejectTestSkips(orchestrator, orchestratorContent, errors);

        Path publisher = WORKFLOWS.resolve("publish-release.yml");
        String publisherContent = requireTokens(publisher, orderedMap(
                "environment: release", "protected publication approval",
                "group: fresnel-release-publication", "serialized publication",
                "ref: ${{ github.sha }}", "exact candidate checkout",
                "EXPECTED_MAIN_SHA", "stale-main protection",
                "attest-build-provenance", "artifact provenance",
                "provenance: mode=max", "container provenance",
                "sbom: true", "container SBOM",
                "ACTUAL_FILES", "version-only candidate validation",
                "Fast-forward main to candidate", "non-force main promotion"), errors);
        rejectTestSkips(publisher, publisherContent, errors);

        Path packager = WORKFLOWS.resolve("release-package.yml");
        String packagerContent = requireTokens(packager, orderedMap(
                "Test and build jar + ZIP/tar.gz", "tested release package production",
                "publish-packages", "single package publication job",
                "environment: release", "protected package attachment approval",
                "refs/tags/${TAG}", "immutable-tag package execution",
                "Attach files to GitHub Release", "explicit release attachment step"), errors);
        rejectTestSkips(packager, packagerContent, errors);

        Path completer = WORKFLOWS.resolve("complete-release.yml");
        requireTokens(completer, orderedMap(
                "workflows: [Publish Release]", "completion tied to publication workflow",
                "UPSTREAM_HEAD_SHA", "exact release-commit correlation",
                "release-package.yml", "platform package completion",
                "--ref \"$RELEASE_TAG\"", "package dispatch from the immutable release tag"), errors);
    }

    private static String requireTokens(
            Path workflow, Map<String, String> required, List<String> errors) throws IOException {
        if (!Files.isRegularFile(workflow)) {
            errors.add("Required workflow is missing: " + ROOT.relativize(workflow));
            return null;
        }

        String content = Files.readString(workflow, UTF_8);
        required.forEach((token, purpose) -> {
            if (!content.contains(token)) {
                errors.add(workflow.getFileName() + " is missing '" + token
                        + "' required for " + purpose);
            }
        });
        return content;
    }

    private static void rejectTestSkips(
            Path workflow, String content, List<String> errors) {
        if (content == null) {
            return;
        }
        if (content.contains("skip_tests")) {
            errors.add(workflow.getFileName()
                    + " contains 'skip_tests': production releases must never offer a test-skip path");
        }
        if (content.contains("-DskipTests")) {
            errors.add(workflow.getFileName()
                    + " contains '-DskipTests': the release build must execute tests");
        }
    }

    private static Map<String, String> orderedMap(String... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("Token/purpose entries must be paired");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put(entries[index], entries[index + 1]);
        }
        return result;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String location(Path file, int line) {
        return ROOT.relativize(file) + ":" + line;
    }
}
