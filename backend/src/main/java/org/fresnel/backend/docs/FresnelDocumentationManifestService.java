package org.fresnel.backend.docs;

import org.fresnel.backend.api.FresnelJobExecutionResult;
import org.fresnel.backend.api.FresnelJobExecutor;
import org.fresnel.backend.api.GeneratedArtifact;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Discovers documentation jobs and emits a deterministic, verified manifest. */
@Service
public final class FresnelDocumentationManifestService {

    private final FresnelJobExecutor executor;
    private final ObjectMapper mapper;

    public FresnelDocumentationManifestService(
            FresnelJobExecutor executor,
            ObjectMapper mapper) {
        if (executor == null) throw new IllegalArgumentException("executor must not be null");
        if (mapper == null) throw new IllegalArgumentException("mapper must not be null");
        this.executor = executor;
        this.mapper = mapper;
    }

    public FresnelDocumentationManifest generate(Path jobRoot, Path assetRoot)
            throws IOException {
        Path normalizedJobRoot = requireDirectory(jobRoot, "documentation job root");
        Path normalizedAssetRoot = requireDirectory(assetRoot, "documentation asset root");
        String jobRootDisplay = portable(jobRoot.normalize());
        String assetRootDisplay = portable(assetRoot.normalize());

        List<Path> jobs = discoverJobs(normalizedJobRoot);
        if (jobs.isEmpty()) {
            throw new IllegalArgumentException(
                    "No .fresnel jobs found beneath " + normalizedJobRoot);
        }

        List<FresnelDocumentationManifest.Example> examples = new ArrayList<>();
        Set<String> exampleIds = new HashSet<>();
        Set<String> artifactPaths = new HashSet<>();

        for (Path jobPath : jobs) {
            Map<String, byte[]> generated = new HashMap<>();
            FresnelJobExecutionResult result = executor.execute(
                    Files.readAllBytes(jobPath),
                    (artifact, content) -> generated.put(artifact.filename(), content.clone()));

            Path relativeJob = normalizedJobRoot.relativize(jobPath);
            String relativeWithoutExtension = portable(relativeJob);
            relativeWithoutExtension = relativeWithoutExtension.substring(
                    0, relativeWithoutExtension.length() - ".fresnel".length());
            String exampleId = relativeWithoutExtension;
            if (!exampleIds.add(exampleId)) {
                throw new IllegalStateException(
                        "Duplicate documentation example id: " + exampleId);
            }

            List<FresnelDocumentationManifest.Artifact> artifacts = new ArrayList<>();
            for (GeneratedArtifact artifact : result.artifacts()) {
                String relativeArtifact = result.job().plugin().id()
                        + "/" + artifact.filename();
                String displayPath = appendPortable(assetRootDisplay, relativeArtifact);
                if (!artifactPaths.add(displayPath)) {
                    throw new IllegalStateException(
                            "More than one documentation job claims artifact " + displayPath);
                }

                Path tracked = normalizedAssetRoot.resolve(relativeArtifact).normalize();
                if (!tracked.startsWith(normalizedAssetRoot)
                        || !Files.isRegularFile(tracked)) {
                    throw new IllegalStateException(
                            "Missing or unsafe documentation artifact: " + tracked);
                }
                String trackedHash = FresnelJobExecutor.normalizedSha256(
                        artifact.mediaType(), Files.readAllBytes(tracked), artifact.dpi());
                if (!trackedHash.equals(artifact.normalizedSha256())) {
                    throw new IllegalStateException(
                            "Stale documentation artifact " + tracked
                                    + ": tracked normalized SHA-256 " + trackedHash
                                    + " but job generated " + artifact.normalizedSha256());
                }
                if (!generated.containsKey(artifact.filename())) {
                    throw new IllegalStateException(
                            "Executor did not provide content for " + artifact.filename());
                }

                artifacts.add(new FresnelDocumentationManifest.Artifact(
                        artifact.outputId(),
                        displayPath,
                        artifact.mediaType(),
                        artifact.sizeBytes(),
                        artifact.widthPx(),
                        artifact.heightPx(),
                        artifact.dpi(),
                        artifact.normalizedSha256()));
            }

            examples.add(new FresnelDocumentationManifest.Example(
                    exampleId,
                    result.job().plugin().id(),
                    appendPortable(jobRootDisplay, portable(relativeJob)),
                    result.job().formatVersion(),
                    result.job().plugin().parameterSchemaVersion(),
                    result.job().plugin().algorithmVersion(),
                    result.job().provenance().parameterSha256(),
                    artifacts));
        }

        return new FresnelDocumentationManifest(
                FresnelDocumentationManifest.CURRENT_FORMAT_VERSION,
                examples);
    }

    public byte[] write(FresnelDocumentationManifest manifest) throws IOException {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
    }

    private static List<Path> discoverJobs(Path root) throws IOException {
        List<Path> jobs = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".fresnel"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(jobs::add);
        }
        return jobs;
    }

    private static Path requireDirectory(Path path, String label) {
        if (path == null) throw new IllegalArgumentException(label + " must not be null");
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException(label + " is not a directory: " + path);
        }
        return normalized;
    }

    private static String appendPortable(String root, String relative) {
        if (root == null || root.isBlank() || ".".equals(root)) return relative;
        return root.endsWith("/") ? root + relative : root + "/" + relative;
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }
}
