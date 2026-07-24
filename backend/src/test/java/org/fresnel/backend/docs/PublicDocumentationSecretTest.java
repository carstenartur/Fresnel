package org.fresnel.backend.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PublicDocumentationSecretTest {

    private static final Pattern PASSWORD_ASSIGNMENT = Pattern.compile(
            "(?im)(?:^|\\s)(?:export\\s+|-e\\s+)?[A-Z][A-Z0-9_]*PASSWORD"
                    + "\\s*=\\s*(?!\\$\\{)[^\\s\\\\]+"
    );

    private static final List<String> FORBIDDEN_CREDENTIAL_EXAMPLES = List.of(
            "correct-horse-battery-staple",
            "violet-meteor-archive-2026",
            "database-specific-secret",
            "| `user` | `user` |",
            "| `admin` | `admin` |",
            "user/user",
            "admin/admin"
    );

    @Test
    void publicOperationalDocumentationContainsNoPasswordValues() throws IOException {
        Path root = repositoryRoot();
        List<Path> documents = List.of(
                root.resolve("README.md"),
                root.resolve("packaging/README-install.md"),
                root.resolve("docs/security/deployment.md")
        );

        for (Path document : documents) {
            assertThat(document)
                    .as("public documentation file")
                    .isRegularFile();

            String content = Files.readString(document, StandardCharsets.UTF_8);
            assertThat(content)
                    .as("known credential examples in %s", root.relativize(document))
                    .doesNotContain(FORBIDDEN_CREDENTIAL_EXAMPLES.toArray(String[]::new));

            Matcher assignment = PASSWORD_ASSIGNMENT.matcher(content);
            boolean found = assignment.find();
            String matchedText = found ? assignment.group() : "<none>";
            assertThat(found)
                    .as("literal password assignment in %s: %s",
                            root.relativize(document), matchedText)
                    .isFalse();
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve("README.md"))
                    && Files.isDirectory(current.resolve("backend"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate the Fresnel repository root");
    }
}
