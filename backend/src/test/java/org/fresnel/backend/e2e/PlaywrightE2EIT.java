package org.fresnel.backend.e2e;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PlaywrightE2EIT {

    private static final Duration MAXIMUM_RUNTIME = Duration.ofMinutes(20);

    @Test
    void completePlaywrightSuitePassesAgainstTheMavenStartedBackend() throws Exception {
        Path backend = Path.of(System.getProperty("basedir", "."))
                .toAbsolutePath()
                .normalize();
        Path frontend = backend.resolveSibling("frontend");
        Path nodeDirectory = backend.resolve("target/node");
        Path log = backend.resolve("target/failsafe-reports/playwright-e2e.log");
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        Path node = nodeDirectory.resolve(windows ? "node.exe" : "node");
        Path npmCli = nodeDirectory.resolve("node_modules/npm/bin/npm-cli.js");

        assertThat(frontend.resolve("package.json"))
                .as("frontend project")
                .isRegularFile();
        assertThat(node)
                .as("Node executable installed by frontend-maven-plugin")
                .isRegularFile();
        assertThat(npmCli)
                .as("npm CLI installed by frontend-maven-plugin")
                .isRegularFile();

        Files.createDirectories(log.getParent());
        Files.deleteIfExists(log);

        ProcessBuilder command = new ProcessBuilder(
                node.toString(), npmCli.toString(), "run", "e2e");
        command.directory(frontend.toFile());
        command.redirectErrorStream(true);
        command.redirectOutput(log.toFile());
        command.environment().put("CI", "true");
        command.environment().put("E2E_USER", "user");
        command.environment().put("E2E_PASSWORD", "user");

        Process process = command.start();
        try {
            if (!process.waitFor(MAXIMUM_RUNTIME.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(30, TimeUnit.SECONDS);
                fail("Playwright did not finish within " + MAXIMUM_RUNTIME
                        + System.lineSeparator() + readLog(log));
            }
            if (process.exitValue() != 0) {
                fail("Playwright exited with status " + process.exitValue()
                        + System.lineSeparator() + readLog(log));
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String readLog(Path log) {
        try {
            return Files.isRegularFile(log)
                    ? Files.readString(log, UTF_8)
                    : "Playwright log was not created: " + log;
        } catch (IOException exception) {
            return "Could not read Playwright log " + log + ": " + exception.getMessage();
        }
    }
}
