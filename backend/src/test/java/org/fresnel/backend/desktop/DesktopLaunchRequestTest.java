package org.fresnel.backend.desktop;

import org.fresnel.backend.api.FresnelJobDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopLaunchRequestTest {

    @TempDir Path tempDir;

    @Test
    void acceptsOrdinaryStartDirectFileAndOpenOption() throws Exception {
        DesktopLaunchRequest ordinary = DesktopLaunchRequest.parse(new String[0]);
        assertTrue(ordinary.jobFile().isEmpty());
        assertTrue(ordinary.springArguments().isEmpty());

        Path job = writeJob("Zonenplatte ü 1.fresnel", "{}");
        DesktopLaunchRequest direct = DesktopLaunchRequest.parse(new String[]{job.toString()});
        assertEquals(job.toAbsolutePath().normalize(), direct.jobFile().orElseThrow());

        DesktopLaunchRequest explicit = DesktopLaunchRequest.parse(new String[]{"--open", job.toString()});
        assertEquals(job.toAbsolutePath().normalize(), explicit.jobFile().orElseThrow());
    }

    @Test
    void passesOnlyArgumentsAfterTheSeparatorToSpring() throws Exception {
        Path job = writeJob("example.fresnel", "{}");
        DesktopLaunchRequest request = DesktopLaunchRequest.parse(new String[]{
                job.toString(), "--", "--server.port=9090", "--logging.level.root=INFO"
        });
        assertEquals(List.of("--server.port=9090", "--logging.level.root=INFO"),
                request.springArguments());
    }

    @Test
    void rejectsAmbiguousAndReservedArguments() throws Exception {
        Path first = writeJob("first.fresnel", "{}");
        Path second = writeJob("second.fresnel", "{}");

        assertThrows(IllegalArgumentException.class,
                () -> DesktopLaunchRequest.parse(new String[]{first.toString(), second.toString()}));
        assertThrows(IllegalArgumentException.class,
                () -> DesktopLaunchRequest.parse(new String[]{"--open"}));
        assertThrows(IllegalArgumentException.class,
                () -> DesktopLaunchRequest.parse(new String[]{"--unknown"}));
        assertThrows(IllegalArgumentException.class,
                () -> DesktopLaunchRequest.parse(new String[]{"--", "--server.address=0.0.0.0"}));
        assertThrows(IllegalArgumentException.class,
                () -> DesktopLaunchRequest.parse(new String[]{
                        "--", "--fresnel.desktop.instance-secret=attacker"
                }));
    }

    @Test
    void rejectsDirectoriesOtherExtensionsEmptyAndOversizedFiles() throws Exception {
        Path json = writeJob("legacy.json", "{}");
        Path empty = writeJob("empty.fresnel", "");
        Path oversized = tempDir.resolve("oversized.fresnel");
        Files.write(oversized, new byte[FresnelJobDocument.MAX_FILE_BYTES + 1]);

        assertThrows(IllegalArgumentException.class,
                () -> DesktopLaunchRequest.parse(new String[]{tempDir.toString()}));
        assertThrows(IllegalArgumentException.class,
                () -> DesktopLaunchRequest.parse(new String[]{json.toString()}));
        assertThrows(IllegalArgumentException.class,
                () -> DesktopLaunchRequest.parse(new String[]{empty.toString()}));
        assertThrows(IllegalArgumentException.class,
                () -> DesktopLaunchRequest.parse(new String[]{oversized.toString()}));
    }

    private Path writeJob(String filename, String content) throws Exception {
        Path path = tempDir.resolve(filename);
        Files.writeString(path, content);
        return path;
    }
}
