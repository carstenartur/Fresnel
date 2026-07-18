package org.fresnel.backend.docs;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Shared path, discovery and atomic-write rules for documentation tooling. */
final class FresnelDocumentationFiles {

    private FresnelDocumentationFiles() {}

    static Path requireDirectory(Path path, String label) throws IOException {
        if (path == null) throw new IllegalArgumentException(label + " must not be null");
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException(label + " is not a directory: " + path);
        }
        // Resolve a caller-selected symbolic root once, then enforce all descendant
        // checks against its physical location.
        return normalized.toRealPath();
    }

    static List<Path> discoverJobs(Path root) throws IOException {
        Path normalizedRoot = requireDirectory(root, "documentation job root");
        List<Path> jobs = new ArrayList<>();
        try (var paths = Files.walk(normalizedRoot)) {
            paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".fresnel"))
                    .sorted(Comparator.comparing(
                            path -> portable(normalizedRoot.relativize(path))))
                    .forEach(jobs::add);
        }
        return jobs;
    }

    static Path requireRegularDescendant(
            Path root,
            Path relative,
            String label) throws IOException {
        if (root == null) throw new IllegalArgumentException(label + " root must not be null");
        if (relative == null || relative.isAbsolute()) {
            throw new IllegalArgumentException(label + " path must be relative: " + relative);
        }

        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target = normalizedRoot.resolve(relative).normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException(label + " escapes its selected root: " + relative);
        }

        // Repository documentation artifacts and jobs must be ordinary files. Do
        // not allow a checked-in symlink, or a symlink in an intermediate segment,
        // to redirect verification to an unrelated runner file.
        Path current = normalizedRoot;
        for (Path segment : normalizedRoot.relativize(target)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalStateException(label + " must not use symbolic links: " + target);
            }
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Missing " + label + ": " + target);
        }
        return target;
    }

    static byte[] readRegularFile(Path path, String label) throws IOException {
        if (path == null) throw new IllegalArgumentException(label + " must not be null");
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(label + " is not an ordinary file: " + path);
        }
        return Files.readAllBytes(normalized);
    }

    static void writeAtomically(Path output, byte[] content) throws IOException {
        if (output == null) throw new IllegalArgumentException("output path must not be null");
        if (content == null) throw new IllegalArgumentException("output content must not be null");

        Path normalized = output.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("output path must have a parent directory: " + output);
        }
        Files.createDirectories(parent);

        Path staged = Files.createTempFile(parent, ".fresnel-docs-", ".tmp");
        try {
            Files.write(staged, content,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(staged, normalized,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staged, normalized, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    static String portableKey(String value) {
        String portable = value.replace('\\', '/');
        return Normalizer.normalize(portable, Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT);
    }
}
