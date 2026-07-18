package org.fresnel.backend.docs;

import org.fresnel.backend.api.FresnelJobExecutionResult;
import org.fresnel.backend.api.FresnelJobExecutor;
import org.fresnel.backend.api.GeneratedArtifact;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
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
        Path normalizedJobRoot = FresnelDocumentationFiles.requireDirectory(
                jobRoot, "documentation job root");
        Path normalizedAssetRoot = FresnelDocumentationFiles.requireDirectory(
                assetRoot, "documentation asset root");
        String jobRootDisplay = documentationPath(normalizedJobRoot);
        String assetRootDisplay = documentationPath(normalizedAssetRoot);

        List<Path> jobs = FresnelDocumentationFiles.discoverJobs(normalizedJobRoot);
        if (jobs.isEmpty()) {
            throw new IllegalArgumentException(
                    "No .fresnel jobs found beneath " + normalizedJobRoot);
        }

        List<FresnelDocumentationManifest.Example> examples = new ArrayList<>();
        Set<String> exampleKeys = new HashSet<>();
        Set<String> artifactPathKeys = new HashSet<>();

        for (Path jobPath : jobs) {
            Map<String, byte[]> generated = new HashMap<>();
            FresnelJobExecutionResult result = executor.execute(
                    FresnelDocumentationFiles.readRegularFile(
                            jobPath, "documentation job"),
                    (artifact, content) -> {
                        byte[] previous = generated.put(
                                artifact.filename(), content.clone());
                        if (previous != null) {
                            throw new IllegalStateException(
                                    "Executor produced duplicate filename " + artifact.filename());
                        }
                    });

            Path relativeJob = normalizedJobRoot.relativize(jobPath);
            String relativeWithoutExtension = FresnelDocumentationFiles.portable(relativeJob);
            relativeWithoutExtension = relativeWithoutExtension.substring(
                    0, relativeWithoutExtension.length() - ".fresnel".length());
            String exampleId = relativeWithoutExtension;
            if (!exampleKeys.add(FresnelDocumentationFiles.portableKey(exampleId))) {
                throw new IllegalStateException(
                        "Duplicate documentation example id on a case-insensitive filesystem: "
                                + exampleId);
            }

            List<FresnelDocumentationManifest.Artifact> artifacts = new ArrayList<>();
            for (GeneratedArtifact artifact : result.artifacts()) {
                String relativeArtifact = result.job().plugin().id()
                        + "/" + artifact.filename();
                String displayPath = appendPortable(assetRootDisplay, relativeArtifact);
                if (!artifactPathKeys.add(FresnelDocumentationFiles.portableKey(displayPath))) {
                    throw new IllegalStateException(
                            "More than one documentation job claims artifact " + displayPath
                                    + " on a case-insensitive filesystem");
                }

                Path tracked = FresnelDocumentationFiles.requireRegularDescendant(
                        normalizedAssetRoot,
                        Path.of(relativeArtifact),
                        "documentation artifact");
                byte[] trackedContent = FresnelDocumentationFiles.readRegularFile(
                        tracked, "documentation artifact");
                String trackedHash = FresnelJobExecutor.normalizedSha256(
                        artifact.mediaType(), trackedContent, artifact.dpi());
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
                        trackedContent.length,
                        artifact.widthPx(),
                        artifact.heightPx(),
                        artifact.dpi(),
                        artifact.normalizedSha256()));
            }

            examples.add(new FresnelDocumentationManifest.Example(
                    exampleId,
                    result.job().plugin().id(),
                    appendPortable(
                            jobRootDisplay,
                            FresnelDocumentationFiles.portable(relativeJob)),
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

    /** Serializes deterministically as UTF-8 and terminates the text file with one LF. */
    public byte[] write(FresnelDocumentationManifest manifest) throws IOException {
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest);
        return (json + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Makes manifests stable whether Maven starts in the repository root or a
     * module directory by keeping only the repository-relative `docs/...` suffix.
     */
    private static String documentationPath(Path path) {
        Path normalized = path.normalize();
        for (int index = 0; index < normalized.getNameCount(); index++) {
            if ("docs".equals(normalized.getName(index).toString())) {
                return FresnelDocumentationFiles.portable(
                        normalized.subpath(index, normalized.getNameCount()));
            }
        }
        return FresnelDocumentationFiles.portable(normalized);
    }

    private static String appendPortable(String root, String relative) {
        if (root == null || root.isBlank() || ".".equals(root)) return relative;
        return root.endsWith("/") ? root + relative : root + "/" + relative;
    }
}
