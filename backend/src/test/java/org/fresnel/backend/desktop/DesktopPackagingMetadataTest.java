package org.fresnel.backend.desktop;

import org.fresnel.backend.api.FresnelJobDocument;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopPackagingMetadataTest {

    @Test
    void sharedAssociationUsesTheCanonicalExtensionAndMediaType() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(repositoryFile(
                "packaging/jpackage/fresnel-job.properties"))) {
            properties.load(input);
        }

        assertEquals(FresnelJobDocument.FILE_EXTENSION.substring(1),
                properties.getProperty("extension"));
        assertEquals(FresnelJobDocument.MEDIA_TYPE, properties.getProperty("mime-type"));
        assertEquals("Fresnel production job", properties.getProperty("description"));
    }

    @Test
    void windowsAndLinuxPackagesEnableTheLauncherAndAssociation() throws Exception {
        for (String script : List.of(
                "packaging/jpackage/build-linux.sh",
                "packaging/jpackage/build-windows.cmd")) {
            String content = Files.readString(repositoryFile(script));
            assertTrue(content.contains("--file-associations"), script);
            assertTrue(content.contains("-Dfresnel.desktop.enabled=true"), script);
            assertTrue(content.contains("fresnel-job.properties"), script);
        }
    }

    private static Path repositoryFile(String relativePath) {
        for (Path candidate : List.of(Path.of(relativePath), Path.of("..").resolve(relativePath))) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("Could not locate repository file: " + relativePath);
    }
}
