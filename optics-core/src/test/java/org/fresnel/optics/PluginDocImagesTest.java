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
 * Integration tests that render each plugin and write example PNG images to
 * {@code docs/assets/plugins/<plugin>/} for use in plugin documentation.
 *
 * <p>Each test renders one or more representative configurations, validates the
 * output, and writes the resulting image(s) to the documentation assets directory.
 * All rendering is deterministic; the same parameters always produce the same image.
 *
 * <p>To regenerate the documentation images, run:
 * <pre>
 *   mvn -pl optics-core test -Dtest=PluginDocImagesTest
 * </pre>
 */
class PluginDocImagesTest {

    /**
     * Resolves {@code docs/assets/plugins/<name>} by walking up from the
     * current working directory until the project root (containing
     * {@code optics-core/}) is found.
     */
    private static Path pluginDir(String name) throws IOException {
        Path cur = Path.of("").toAbsolutePath();
        while (cur != null && !Files.isDirectory(cur.resolve("optics-core"))) {
            cur = cur.getParent();
        }
        if (cur == null) {
            cur = Path.of("").toAbsolutePath();
        }
        Path dir = cur.resolve("docs/assets/plugins/" + name);
        Files.createDirectories(dir);
        return dir;
    }

    private static void savePng(BufferedImage img, Path dir, String filename) throws IOException {
        ImageIO.write(img, "PNG", dir.resolve(filename).toFile());
    }

    // -------------------------------------------------------------------------
    // Zone Plate
    // -------------------------------------------------------------------------

    /**
     * Generates three example images for the Single Zone Plate plugin:
     * binary amplitude (positive/negative polarity) and greyscale phase.
     */
    @Test
    void zonePlate_generateDocImages() throws IOException {
        Path dir = pluginDir("zone-plate");

        // On-axis, binary amplitude, positive polarity (10 mm @ 1200 dpi → ~473 px)
        SingleZonePlateParameters onAxis = SingleZonePlateParameters.onAxis(
                10.0, 250.0, 550.0, 1200.0);
        RenderResult rOnAxis = ZonePlateRenderer.render(onAxis);
        assertNotNull(rOnAxis.image());
        assertTrue(rOnAxis.image().getWidth() > 0);
        savePng(rOnAxis.image(), dir, "on-axis.png");

        // Greyscale phase mask
        SingleZonePlateParameters greyscale = new SingleZonePlateParameters(
                10.0, 250.0, 550.0, 1200.0, 0.0, 0.0,
                MaskType.GREYSCALE_PHASE, Polarity.POSITIVE);
        RenderResult rGrey = ZonePlateRenderer.render(greyscale);
        savePng(rGrey.image(), dir, "greyscale-phase.png");

        // Negative polarity (inverted binary amplitude)
        SingleZonePlateParameters negative = new SingleZonePlateParameters(
                10.0, 250.0, 550.0, 1200.0, 0.0, 0.0,
                MaskType.BINARY_AMPLITUDE, Polarity.NEGATIVE);
        RenderResult rNeg = ZonePlateRenderer.render(negative);
        savePng(rNeg.image(), dir, "negative-polarity.png");
    }

    // -------------------------------------------------------------------------
    // RGB Zone Plate
    // -------------------------------------------------------------------------

    /**
     * Generates one RGB composite example image for the RGB Zone Plate plugin.
     */
    @Test
    void rgbZonePlate_generateDocImages() throws IOException {
        Path dir = pluginDir("rgb-zone-plate");

        SingleZonePlateParameters base = SingleZonePlateParameters.onAxis(
                10.0, 250.0, 550.0, 1200.0);
        RenderResult rgb = RgbZonePlateRenderer.render(base, 630.0, 532.0, 450.0);
        assertNotNull(rgb.image());
        assertTrue(rgb.image().getWidth() > 0);
        savePng(rgb.image(), dir, "rgb.png");
    }

    // -------------------------------------------------------------------------
    // Multi-Focus
    // -------------------------------------------------------------------------

    /**
     * Generates two example images for the Multi-Focus plugin:
     * a two-point design and a line-focus design.
     */
    @Test
    void multiFocus_generateDocImages() throws IOException {
        Path dir = pluginDir("multi-focus");

        // Two discrete foci
        MultiFocusParameters twoFoci = new MultiFocusParameters(
                10.0,
                List.of(
                        new MultiFocusParameters.FocusPoint(-3.0, 0.0, 300.0),
                        new MultiFocusParameters.FocusPoint(+3.0, 0.0, 300.0)),
                550.0, 1200.0,
                MaskType.BINARY_AMPLITUDE, Polarity.POSITIVE);
        RenderResult rTwo = MultiFocusRenderer.render(twoFoci);
        assertNotNull(rTwo.image());
        assertTrue(rTwo.image().getWidth() > 0);
        savePng(rTwo.image(), dir, "two-foci.png");

        // Line focus with 5 points
        List<MultiFocusParameters.FocusPoint> line =
                MultiFocusParameters.lineOfPoints(-4, 0, 400, 4, 0, 400, 5);
        MultiFocusParameters lineFocus = new MultiFocusParameters(
                10.0, line, 550.0, 1200.0,
                MaskType.BINARY_AMPLITUDE, Polarity.POSITIVE);
        RenderResult rLine = MultiFocusRenderer.render(lineFocus);
        savePng(rLine.image(), dir, "line-focus.png");
    }

    // -------------------------------------------------------------------------
    // Hex Macro Cell
    // -------------------------------------------------------------------------

    /**
     * Generates one example image for the Hex Macro Cell plugin.
     */
    @Test
    void hexMacroCell_generateDocImages() throws IOException {
        Path dir = pluginDir("hex-macro-cell");

        // 15 mm radius macro cell with 5 mm sub-elements @ 400 dpi → ~473 px (30 mm diameter)
        HexMacroCellParameters p = HexMacroCellParameters.onAxis(
                15.0, 5.0, 5.5, 500.0, 550.0, 400.0);
        RenderResult r = HexMacroCellRenderer.render(p);
        assertNotNull(r.image());
        assertTrue(r.image().getWidth() > 0);
        savePng(r.image(), dir, "on-axis.png");
    }

    // -------------------------------------------------------------------------
    // Window Foil
    // -------------------------------------------------------------------------

    /**
     * Generates one example image for the Window Foil plugin.
     */
    @Test
    void windowFoil_generateDocImages() throws IOException {
        Path dir = pluginDir("window-foil");

        // 60 mm × 40 mm sheet at 200 dpi → ~473 × 315 px
        WindowFoilParameters p = new WindowFoilParameters(
                60.0, 40.0, 12.0, 4.0, 4.5, 550.0, 200.0,
                MaskType.BINARY_AMPLITUDE, Polarity.POSITIVE,
                List.of(WindowFoilParameters.CellSpec.onAxis(1000.0)),
                true);
        RenderResult r = WindowFoilRenderer.render(p);
        assertNotNull(r.image());
        assertTrue(r.image().getWidth() > 0);
        savePng(r.image(), dir, "foil-sheet.png");
    }

    // -------------------------------------------------------------------------
    // Hologram
    // -------------------------------------------------------------------------

    /**
     * Generates three images for the Hologram plugin:
     * the synthetic checker target, the synthesised phase mask and its
     * optical reconstruction.
     */
    @Test
    void hologram_generateDocImages() throws IOException {
        Path dir = pluginDir("hologram");

        // 512×512 checker target (power-of-two, max detail within API limits)
        BufferedImage target = HologramParameters.syntheticCheckerTarget(512, 8);
        savePng(target, dir, "target.png");

        // Gerchberg–Saxton synthesis (deterministic seed via default overload)
        HologramParameters p = new HologramParameters(
                target, 100, HologramParameters.OutputType.GREYSCALE_PHASE, 1200.0);
        RenderResult r = HologramSynthesizer.synthesize(p);
        assertNotNull(r.image());
        assertTrue(r.image().getWidth() > 0);
        savePng(r.image(), dir, "hologram-mask.png");

        // Simulated optical reconstruction
        BufferedImage recon = HologramSynthesizer.reconstruct(
                r.image(), HologramParameters.OutputType.GREYSCALE_PHASE);
        savePng(recon, dir, "reconstruction.png");
    }
}
