package org.fresnel.backend.docs;

import org.fresnel.backend.FresnelBackendApplication;
import org.fresnel.backend.api.DirectoryFresnelJobOutputSink;
import org.fresnel.backend.api.FresnelJobExecutionResult;
import org.fresnel.backend.api.FresnelJobExecutor;
import org.fresnel.backend.api.GeneratedArtifact;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Command-line consumer of the same canonical job executor used by the application. */
public final class FresnelDocsCli {

    private FresnelDocsCli() {}

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                FresnelBackendApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.main.banner-mode=off",
                        "spring.jpa.open-in-view=false")
                .run()) {
            run(args, context.getBean(FresnelJobExecutor.class), System.out);
        }
    }

    static void run(String[] args, FresnelJobExecutor executor, PrintStream out) throws Exception {
        if (args == null || args.length == 0) throw usage();
        switch (args[0]) {
            case "render" -> {
                requireArguments(args, 3);
                renderOne(executor, Path.of(args[1]), Path.of(args[2]), out);
            }
            case "verify" -> {
                requireArguments(args, 3);
                verifyOne(executor, Path.of(args[1]), Path.of(args[2]), out);
            }
            case "render-all" -> {
                requireArguments(args, 3);
                executeAll(executor, Path.of(args[1]), Path.of(args[2]), true, out);
            }
            case "verify-all" -> {
                requireArguments(args, 3);
                executeAll(executor, Path.of(args[1]), Path.of(args[2]), false, out);
            }
            case "list" -> {
                requireArguments(args, 2);
                list(executor, Path.of(args[1]), out);
            }
            default -> throw usage();
        }
    }

    private static void renderOne(
            FresnelJobExecutor executor,
            Path job,
            Path outputDirectory,
            PrintStream out) throws Exception {
        FresnelJobExecutionResult result = executor.execute(
                Files.readAllBytes(job),
                new DirectoryFresnelJobOutputSink(outputDirectory));
        printResult(job, result, out, "rendered");
    }

    private static void verifyOne(
            FresnelJobExecutor executor,
            Path job,
            Path expectedDirectory,
            PrintStream out) throws Exception {
        Map<String, byte[]> generated = new HashMap<>();
        FresnelJobExecutionResult result = executor.execute(
                Files.readAllBytes(job),
                (artifact, content) -> generated.put(artifact.filename(), content.clone()));
        verifyArtifacts(result, generated, expectedDirectory);
        printResult(job, result, out, "verified");
    }

    private static void executeAll(
            FresnelJobExecutor executor,
            Path jobRoot,
            Path assetRoot,
            boolean render,
            PrintStream out) throws Exception {
        List<Path> jobs = discoverJobs(jobRoot);
        if (jobs.isEmpty()) {
            throw new IllegalArgumentException("No .fresnel jobs found beneath " + jobRoot);
        }

        Set<String> claimedTargets = new HashSet<>();
        for (Path job : jobs) {
            if (render) {
                FresnelJobExecutionResult parsed = executor.execute(
                        Files.readAllBytes(job),
                        (artifact, content) -> {
                            // First pass captures metadata only. The real directory is
                            // selected from the normalized plugin id below.
                        });
                Path targetDirectory = assetRoot.resolve(parsed.job().plugin().id());
                ensureUniqueTargets(parsed, claimedTargets);
                FresnelJobExecutionResult written = executor.execute(
                        parsed.job(), new DirectoryFresnelJobOutputSink(targetDirectory));
                printResult(job, written, out, "rendered");
            } else {
                Map<String, byte[]> generated = new HashMap<>();
                FresnelJobExecutionResult result = executor.execute(
                        Files.readAllBytes(job),
                        (artifact, content) -> generated.put(artifact.filename(), content.clone()));
                ensureUniqueTargets(result, claimedTargets);
                verifyArtifacts(result, generated, assetRoot.resolve(result.job().plugin().id()));
                printResult(job, result, out, "verified");
            }
        }
    }

    private static void list(
            FresnelJobExecutor executor,
            Path jobRoot,
            PrintStream out) throws Exception {
        for (Path job : discoverJobs(jobRoot)) {
            FresnelJobExecutionResult result = executor.execute(
                    Files.readAllBytes(job), (artifact, content) -> {});
            out.printf("%s | %s | %s%n",
                    jobRoot.toAbsolutePath().normalize().relativize(job.toAbsolutePath().normalize()),
                    result.job().plugin().id(),
                    result.artifacts().stream().map(GeneratedArtifact::filename).toList());
        }
    }

    private static void verifyArtifacts(
            FresnelJobExecutionResult result,
            Map<String, byte[]> generated,
            Path expectedDirectory) throws IOException {
        for (GeneratedArtifact artifact : result.artifacts()) {
            Path expected = expectedDirectory.resolve(artifact.filename()).normalize();
            if (!expected.toAbsolutePath().normalize().startsWith(
                    expectedDirectory.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException(
                        "Expected artifact escaped selected directory: " + artifact.filename());
            }
            if (!Files.isRegularFile(expected)) {
                throw new IllegalStateException("Missing documentation artifact: " + expected);
            }
            byte[] expectedBytes = Files.readAllBytes(expected);
            String expectedHash = FresnelJobExecutor.normalizedSha256(
                    artifact.mediaType(), expectedBytes, artifact.dpi());
            if (!expectedHash.equals(artifact.normalizedSha256())) {
                throw new IllegalStateException(
                        "Stale documentation artifact " + expected
                                + ": expected normalized SHA-256 " + expectedHash
                                + " but job generated " + artifact.normalizedSha256());
            }
            if (!generated.containsKey(artifact.filename())) {
                throw new IllegalStateException(
                        "Executor did not provide content for " + artifact.filename());
            }
        }
    }

    private static void ensureUniqueTargets(
            FresnelJobExecutionResult result,
            Set<String> claimedTargets) {
        for (GeneratedArtifact artifact : result.artifacts()) {
            String target = result.job().plugin().id() + "/" + artifact.filename();
            if (!claimedTargets.add(target)) {
                throw new IllegalStateException(
                        "More than one documentation job claims artifact " + target);
            }
        }
    }

    private static List<Path> discoverJobs(Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("Documentation job root is not a directory: " + root);
        }
        List<Path> jobs = new ArrayList<>();
        try (var paths = Files.walk(normalized)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".fresnel"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(jobs::add);
        }
        return jobs;
    }

    private static void printResult(
            Path job,
            FresnelJobExecutionResult result,
            PrintStream out,
            String verb) {
        for (GeneratedArtifact artifact : result.artifacts()) {
            out.printf("%s %s -> %s (%s, %d bytes, %s)%n",
                    verb,
                    job,
                    artifact.filename(),
                    artifact.mediaType(),
                    artifact.sizeBytes(),
                    artifact.normalizedSha256());
        }
    }

    private static void requireArguments(String[] args, int count) {
        if (args.length != count) throw usage();
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException("""
                Usage:
                  FresnelDocsCli render <job.fresnel> <output-directory>
                  FresnelDocsCli verify <job.fresnel> <expected-directory>
                  FresnelDocsCli render-all <job-root> <asset-root>
                  FresnelDocsCli verify-all <job-root> <asset-root>
                  FresnelDocsCli list <job-root>
                """);
    }
}
