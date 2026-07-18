package org.fresnel.backend.docs;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.fresnel.backend.api.DirectoryFresnelJobOutputSink;
import org.fresnel.backend.api.FresnelJobDocument;
import org.fresnel.backend.api.FresnelJobExecutionResult;
import org.fresnel.backend.api.FresnelJobExecutor;
import org.fresnel.backend.api.FresnelJobService;
import org.fresnel.backend.api.GeneratedArtifact;
import org.fresnel.backend.api.PluginSchemaService;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Command-line consumer of the same canonical job services used by the application. */
public final class FresnelDocsCli {

    private FresnelDocsCli() {}

    /**
     * Starts only the data-oriented services needed for job execution. The CLI does
     * not bootstrap Spring MVC, JPA, security, a servlet container or a database.
     */
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        ObjectMapper mapper = new ObjectMapper();
        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            FresnelJobService jobService = new FresnelJobService(
                    mapper, validatorFactory.getValidator());
            PluginSchemaService schemaService = new PluginSchemaService(mapper);
            FresnelJobExecutor executor = new FresnelJobExecutor(jobService, mapper);
            FresnelDocumentationRenderer documentationRenderer =
                    new FresnelDocumentationRenderer(jobService, schemaService);
            FresnelDocumentationManifestService manifestService =
                    new FresnelDocumentationManifestService(executor, mapper);
            run(
                    args,
                    executor,
                    jobService,
                    documentationRenderer,
                    manifestService,
                    System.out);
        }
    }

    static void run(
            String[] args,
            FresnelJobExecutor executor,
            FresnelJobService jobService,
            FresnelDocumentationRenderer documentationRenderer,
            FresnelDocumentationManifestService manifestService,
            PrintStream out) throws Exception {
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
                list(jobService, Path.of(args[1]), out);
            }
            case "table" -> {
                requireArguments(args, 2);
                out.print(documentationRenderer.renderParameterTable(
                        FresnelDocumentationFiles.readRegularFile(
                                Path.of(args[1]), "documentation job")));
            }
            case "manifest" -> {
                requireArguments(args, 4);
                writeManifest(
                        manifestService,
                        Path.of(args[1]),
                        Path.of(args[2]),
                        Path.of(args[3]),
                        out);
            }
            case "verify-manifest" -> {
                requireArguments(args, 4);
                verifyManifest(
                        manifestService,
                        Path.of(args[1]),
                        Path.of(args[2]),
                        Path.of(args[3]),
                        out);
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
                FresnelDocumentationFiles.readRegularFile(job, "documentation job"),
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
                FresnelDocumentationFiles.readRegularFile(job, "documentation job"),
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
        List<Path> jobs = FresnelDocumentationFiles.discoverJobs(jobRoot);
        if (jobs.isEmpty()) {
            throw new IllegalArgumentException("No .fresnel jobs found beneath " + jobRoot);
        }

        Path normalizedAssetRoot = render
                ? assetRoot.toAbsolutePath().normalize()
                : FresnelDocumentationFiles.requireDirectory(
                        assetRoot, "documentation asset root");

        // Preflight the complete batch before modifying a single tracked asset.
        // Invalid jobs and cross-job filename collisions therefore fail without
        // leaving a partially regenerated documentation tree.
        List<PlannedJob> planned = new ArrayList<>();
        Set<String> claimedTargets = new HashSet<>();
        for (Path job : jobs) {
            Map<String, byte[]> generated = new HashMap<>();
            FresnelJobExecutionResult result = executor.execute(
                    FresnelDocumentationFiles.readRegularFile(
                            job, "documentation job"),
                    (artifact, content) -> generated.put(
                            artifact.filename(), content.clone()));
            ensureUniqueTargets(result, claimedTargets);
            planned.add(new PlannedJob(job, result, generated));
        }

        for (PlannedJob plan : planned) {
            Path targetDirectory = normalizedAssetRoot
                    .resolve(plan.result().job().plugin().id())
                    .normalize();
            if (!targetDirectory.startsWith(normalizedAssetRoot)) {
                throw new IllegalStateException(
                        "Plugin asset directory escaped the selected root: "
                                + plan.result().job().plugin().id());
            }

            if (render) {
                DirectoryFresnelJobOutputSink sink =
                        new DirectoryFresnelJobOutputSink(targetDirectory);
                for (GeneratedArtifact artifact : plan.result().artifacts()) {
                    byte[] content = plan.generated().get(artifact.filename());
                    if (content == null) {
                        throw new IllegalStateException(
                                "Executor did not provide content for " + artifact.filename());
                    }
                    sink.write(artifact, content);
                }
                printResult(plan.job(), plan.result(), out, "rendered");
            } else {
                verifyArtifacts(plan.result(), plan.generated(), targetDirectory);
                printResult(plan.job(), plan.result(), out, "verified");
            }
        }
    }

    private static void list(
            FresnelJobService jobService,
            Path jobRoot,
            PrintStream out) throws Exception {
        Path normalizedRoot = FresnelDocumentationFiles.requireDirectory(
                jobRoot, "documentation job root");
        for (Path job : FresnelDocumentationFiles.discoverJobs(normalizedRoot)) {
            FresnelJobDocument document = jobService.parseAndNormalize(
                    FresnelDocumentationFiles.readRegularFile(
                            job, "documentation job"));
            List<String> filenames = document.production() == null
                    ? List.of()
                    : document.production().outputs().stream()
                    .map(FresnelJobDocument.ProductionOutput::filename)
                    .toList();
            out.printf("%s | %s | %s%n",
                    normalizedRoot.relativize(job),
                    document.plugin().id(),
                    filenames);
        }
    }

    private static void writeManifest(
            FresnelDocumentationManifestService manifestService,
            Path jobRoot,
            Path assetRoot,
            Path output,
            PrintStream out) throws Exception {
        byte[] content = manifestService.write(manifestService.generate(jobRoot, assetRoot));
        FresnelDocumentationFiles.writeAtomically(output, content);
        out.printf("wrote documentation manifest %s (%d bytes)%n",
                output, content.length);
    }

    private static void verifyManifest(
            FresnelDocumentationManifestService manifestService,
            Path jobRoot,
            Path assetRoot,
            Path expected,
            PrintStream out) throws Exception {
        byte[] actual = manifestService.write(manifestService.generate(jobRoot, assetRoot));
        byte[] checkedIn = FresnelDocumentationFiles.readRegularFile(
                expected, "documentation manifest");
        if (!Arrays.equals(checkedIn, actual)) {
            throw new IllegalStateException(
                    "Documentation manifest is stale: " + expected
                            + ". Regenerate it through the manifest command.");
        }
        out.printf("verified documentation manifest %s%n", expected);
    }

    private static void verifyArtifacts(
            FresnelJobExecutionResult result,
            Map<String, byte[]> generated,
            Path expectedDirectory) throws IOException {
        Path normalizedDirectory = FresnelDocumentationFiles.requireDirectory(
                expectedDirectory, "expected artifact directory");
        for (GeneratedArtifact artifact : result.artifacts()) {
            Path expected = FresnelDocumentationFiles.requireRegularDescendant(
                    normalizedDirectory,
                    Path.of(artifact.filename()),
                    "documentation artifact");
            byte[] expectedBytes = FresnelDocumentationFiles.readRegularFile(
                    expected, "documentation artifact");
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
            if (!claimedTargets.add(FresnelDocumentationFiles.portableKey(target))) {
                throw new IllegalStateException(
                        "More than one documentation job claims artifact " + target
                                + " on a case-insensitive filesystem");
            }
        }
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
                  FresnelDocsCli table <job.fresnel>
                  FresnelDocsCli manifest <job-root> <asset-root> <output.json>
                  FresnelDocsCli verify-manifest <job-root> <asset-root> <expected.json>
                """);
    }

    private record PlannedJob(
            Path job,
            FresnelJobExecutionResult result,
            Map<String, byte[]> generated) {}
}
