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
 * Integration tests that render each plugin and validate the output image.
 *
 * <p>By default images are written to {@code target/doc-images/<plugin>/} so
 * that normal CI runs stay clean (no modifications to tracked files).
 *
 * <p>To regenerate the committed documentation assets under
 * {@code docs/assets/plugins/}, pass the system property
 * {@code fresnel.docs=generate}:
 * <pre>
 *   mvn -pl optics-core test -Dtest=PluginDocImagesTest -Dfresnel.docs=generate
 * </pre>
 */
class PluginDocImagesTest {

    /** System-property name that triggers writing to the docs assets directory. */
    private static final String DOCS_PROP = "fresnel.docs";

    /**
     * Returns the output directory for plugin {@code name}.
     *
     * <ul>
     *   <li>When {@code -Dfresnel.docs=generate} is set, resolves
     *       {@code docs/assets/plugins/<name>} under the project root so that
     *       the committed documentation images are updated.</li>
     *   <li>Otherwise writes to {@code target/doc-images/<name>} which is
     *       never committed, keeping normal CI runs side-effect free.</li>
     * </ul>
     */
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

    /** Asserts image dimensions and that the saved file is non-empty. */
    private static void assertImage(BufferedImage img, Path saved, int minWidth, int minHeight) throws IOException {
        assertNotNull(img);
        assertTrue(img.getWidth()  >= minWidth,  "image width "  + img.getWidth()  + " < " + minWidth);
        assertTrue(img.getHeight() >= minHeight, "image height " + img.getHeight() + " < " + minHeight);
        assertTrue(Files.exists(saved),           "file not written: " + saved.getFileName());
        assertTrue(Files.size(saved)  > 0,        "file is empty: "    + saved.getFileName());
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
        Path fOnAxis = savePng(rOnAxis.image(), dir, "on-axis.png");
        assertImage(rOnAxis.image(), fOnAxis, 400, 400);

        // Greyscale phase mask
        SingleZonePlateParameters greyscale = new SingleZonePlateParameters(
                10.0, 250.0, 550.0, 1200.0, 0.0, 0.0,
                MaskType.GREYSCALE_PHASE, Polarity.POSITIVE);
        RenderResult rGrey = ZonePlateRenderer.render(greyscale);
        Path fGrey = savePng(rGrey.image(), dir, "greyscale-phase.png");
        assertImage(rGrey.image(), fGrey, 400, 400);

        // Negative polarity (inverted binary amplitude)
        SingleZonePlateParameters negative = new SingleZonePlateParameters(
                10.0, 250.0, 550.0, 1200.0, 0.0, 0.0,
                MaskType.BINARY_AMPLITUDE, Polarity.NEGATIVE);
        RenderResult rNeg = ZonePlateRenderer.render(negative);
        Path fNeg = savePng(rNeg.image(), dir, "negative-polarity.png");
        assertImage(rNeg.image(), fNeg, 400, 400);
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
        Path fRgb = savePng(rgb.image(), dir, "rgb.png");
        assertImage(rgb.image(), fRgb, 400, 400);
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
        Path fTwo = savePng(rTwo.image(), dir, "two-foci.png");
        assertImage(rTwo.image(), fTwo, 400, 400);

        // Line focus with 5 points
        List<MultiFocusParameters.FocusPoint> line =
                MultiFocusParameters.lineOfPoints(-4, 0, 400, 4, 0, 400, 5);
        MultiFocusParameters lineFocus = new MultiFocusParameters(
                10.0, line, 550.0, 1200.0,
                MaskType.BINARY_AMPLITUDE, Polarity.POSITIVE);
        RenderResult rLine = MultiFocusRenderer.render(lineFocus);
        Path fLine = savePng(rLine.image(), dir, "line-focus.png");
        assertImage(rLine.image(), fLine, 400, 400);
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
        Path f = savePng(r.image(), dir, "on-axis.png");
        assertImage(r.image(), f, 400, 400);
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
        Path f = savePng(r.image(), dir, "foil-sheet.png");
        assertImage(r.image(), f, 400, 250);
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
        Path fTarget = savePng(target, dir, "target.png");
        assertImage(target, fTarget, 512, 512);

        // Gerchberg–Saxton synthesis (deterministic seed via default overload)
        HologramParameters p = new HologramParameters(
                target, 100, HologramParameters.OutputType.GREYSCALE_PHASE, 1200.0);
        RenderResult r = HologramSynthesizer.synthesize(p);
        Path fMask = savePng(r.image(), dir, "hologram-mask.png");
        assertImage(r.image(), fMask, 400, 400);

        // Simulated optical reconstruction
        BufferedImage recon = HologramSynthesizer.reconstruct(
                r.image(), HologramParameters.OutputType.GREYSCALE_PHASE);
        Path fRecon = savePng(recon, dir, "reconstruction.png");
        assertImage(recon, fRecon, 400, 400);
    }
}
