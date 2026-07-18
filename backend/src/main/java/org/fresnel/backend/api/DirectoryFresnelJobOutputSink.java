package org.fresnel.backend.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Writes generated artifacts beneath one caller-selected directory. */
public final class DirectoryFresnelJobOutputSink implements FresnelJobOutputSink {

    private final Path root;

    public DirectoryFresnelJobOutputSink(Path root) {
        if (root == null) throw new IllegalArgumentException("output root must not be null");
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    @Override
    public void write(GeneratedArtifact artifact, byte[] content) throws IOException {
        if (artifact == null) throw new IllegalArgumentException("artifact must not be null");
        if (content == null) throw new IllegalArgumentException("artifact content must not be null");

        Files.createDirectories(root);
        Path target = root.resolve(artifact.filename()).normalize();
        if (!root.equals(target.getParent())) {
            throw new IllegalArgumentException(
                    "generated artifact must remain directly inside the selected output directory: "
                            + artifact.filename());
        }
        Files.write(target, content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }
}
