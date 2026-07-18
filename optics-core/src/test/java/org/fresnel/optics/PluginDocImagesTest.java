package org.fresnel.optics;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the remaining documentation image that has not yet been
 * migrated to a public `.fresnel` production job.
 *
 * <p>Zone Plate, RGB Zone Plate, Multi-Focus, Hex Macro Cell and Hologram examples
 * now come from `docs/jobs/<plugin>/*.fresnel` and are drift-checked through
 * `FresnelJobExecutor` in the backend module.</p>
 *
 * <p>By default the remaining Window Foil image is written to
 * {@code target/doc-images/window-foil/}. To regenerate its committed asset under
 * {@code docs/assets/plugins/}, pass {@code -Dfresnel.docs=generate}.</p>
 */
class PluginDocImagesTest {

    private static final String DOCS_PROP = "fresnel.docs";

    private static Path pluginDir(String name) throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("optics-core"))) {
            root = root.getParent();
        }
        if (root == null) {
            root = Path.of("").toAbsolutePath();
        }
        Path dir = "generate".equals(System.getProperty(DOCS_PROP))
                ? root.resolve("docs/assets/plugins/" + name)
                : root.resolve("optics-core/target/doc-images/" + name);
        Files.createDirectories(dir);
        return dir;
    }

    private static Path savePng(BufferedImage img, Path dir, String filename) throws IOException {
        Path file = dir.resolve(filename);
        ImageIO.write(img, "PNG", file.toFile());
        return file;
    }

    private static void assertImage(
            BufferedImage img,
            Path saved,
            int minWidth,
            int minHeight) throws IOException {
        assertNotNull(img);
        assertTrue(img.getWidth() >= minWidth,
                "image width " + img.getWidth() + " < " + minWidth);
        assertTrue(img.getHeight() >= minHeight,
                "image height " + img.getHeight() + " < " + minHeight);
        assertTrue(Files.exists(saved), "file not written: " + saved.getFileName());
        assertTrue(Files.size(saved) > 0, "file is empty: " + saved.getFileName());
    }

    @Test
    void windowFoil_generateDocImages() throws IOException {
        Path dir = pluginDir("window-foil");

        WindowFoilParameters p = new WindowFoilParameters(
                60.0, 40.0, 12.0, 4.0, 4.5, 550.0, 200.0,
                MaskType.BINARY_AMPLITUDE, Polarity.POSITIVE,
                List.of(WindowFoilParameters.CellSpec.onAxis(1000.0)),
                true);
        RenderResult r = WindowFoilRenderer.render(p);
        Path f = savePng(r.image(), dir, "foil-sheet.png");
        assertImage(r.image(), f, 400, 250);
    }
}
