package org.fresnel.backend.api;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

        // Never stream directly through the final path: Files.write follows an
        // existing symbolic link and could therefore modify a file outside the
        // selected output directory. Stage the complete artifact beside the target
        // and replace the directory entry instead. Files.move replaces the link
        // itself rather than following it and also avoids exposing partial output.
        Path staged = Files.createTempFile(root, ".fresnel-output-", ".tmp");
        try {
            Files.write(staged, content,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(staged, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
