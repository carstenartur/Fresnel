package org.fresnel.optics;

import java.util.List;
import java.util.Map;

/** Code-owned printer profiles; job files can select IDs but cannot inject escape sequences. */
public final class PrinterRasterProfiles {

    public static final String DEFAULT_PROFILE_ID = "pcl5e-a4-600-portrait-v1";

    public static final PrinterRasterProfile PCL5E_A4_600_PORTRAIT = new PrinterRasterProfile(
            DEFAULT_PROFILE_ID,
            1,
            PrinterLanguageDialect.PCL_5E,
            600,
            600,
            4961,
            7016,
            150,
            150,
            4661,
            6716,
            "A4",
            PclPageOrientation.PORTRAIT,
            DeviceAxis.X,
            DeviceAxis.Y,
            java.util.Set.of(PclCompression.NONE, PclCompression.TIFF));

    public static final List<PrinterRasterProfile> ALL = List.of(PCL5E_A4_600_PORTRAIT);
    private static final Map<String, PrinterRasterProfile> BY_ID = Map.of(
            PCL5E_A4_600_PORTRAIT.id(), PCL5E_A4_600_PORTRAIT);

    private PrinterRasterProfiles() {}

    public static PrinterRasterProfile require(String id) {
        String resolved = id == null || id.isBlank() ? DEFAULT_PROFILE_ID : id.trim();
        PrinterRasterProfile profile = BY_ID.get(resolved);
        if (profile == null) throw new IllegalArgumentException("unknown printer raster profile: " + resolved);
        return profile;
    }
}
