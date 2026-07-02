package org.fresnel.optics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Goal-driven Optical Design Assistant for Fresnel diffractive optics.
 *
 * <p>Given a {@link DesignGoal} the assistant generates several plausible Zone Plate candidate
 * designs, evaluates them and returns a ranked {@link DesignRecommendation} with human-readable
 * explanations.
 *
 * <h2>Candidate generation strategy (Zone Plate)</h2>
 * <p>Three aperture diameters are chosen that span the printability range for the given
 * DPI/wavelength/focal-length combination:
 * <ol>
 *   <li><em>Compact</em> — aperture at exactly 5 px per outermost zone (recommended lower bound);
 *       best printability, fewest zones.</li>
 *   <li><em>Balanced</em> — aperture at ~3.5 px per outermost zone; medium trade-off.</li>
 *   <li><em>Wide-aperture</em> — aperture at ~2.5 px per outermost zone; most zones and highest
 *       NA, but near the printability warning limit.</li>
 * </ol>
 * <p>All candidates are clamped to the smallest of: the page dimension and the optional
 * {@link DesignGoal#maxApertureMm()} constraint.
 *
 * <h2>Scoring</h2>
 * <p>A composite score in [0, 1] is computed from four dimensions, normalized across the
 * candidate set so that each run is deterministic:
 * <ul>
 *   <li>40 % printability   — pixels per outermost zone, normalized</li>
 *   <li>30 % focus quality  — numerical aperture, normalized</li>
 *   <li>20 % zone adequacy  — number of Fresnel zones, normalized</li>
 *   <li>10 % fabrication risk — 1.0 (no warnings), 0.5 (warnings), 0.0 (errors)</li>
 * </ul>
 *
 * <h2>Advisory disclaimer</h2>
 * <p>The returned recommendation is advisory and based on the stated physical assumptions.
 * The assistant does not perform wave-optical simulation; it uses the paraxial thin-lens
 * approximation throughout.
 */
public final class DesignAssistant {

    /** Number of Zone Plate candidates generated per recommendation. */
    static final int CANDIDATE_COUNT = 3;

    /** Target pixels per outer zone for the "compact" candidate. */
    private static final double TARGET_PX_COMPACT = 5.0;
    /** Target pixels per outer zone for the "balanced" candidate. */
    private static final double TARGET_PX_BALANCED = 3.5;
    /** Target pixels per outer zone for the "wide" candidate. */
    private static final double TARGET_PX_WIDE = 2.5;

    private DesignAssistant() {}

    /**
     * Generate candidate Zone Plate designs for the given goal and return a ranked recommendation.
     *
     * @param goal user-provided design goal and constraints
     * @return ranked recommendation with the best candidate, alternatives and global warnings
     * @throws IllegalArgumentException if the goal parameters are invalid
     */
    public static DesignRecommendation recommend(DesignGoal goal) {
        double pixelMm = Units.pixelSizeMm(goal.dpi());
        double lambdaMm = Units.nmToMm(goal.wavelengthNm());
        double focalMm = goal.targetFocusMm();

        // Physical aperture constraints
        double maxByPage = Math.min(goal.pageSizeWidthMm(), goal.pageSizeHeightMm());
        double maxAperture = (goal.maxApertureMm() != null)
                ? Math.min(maxByPage, goal.maxApertureMm())
                : maxByPage;

        // Aperture diameters at the target pixels-per-outer-zone thresholds
        double dCompact   = lambdaMm * focalMm / (TARGET_PX_COMPACT   * pixelMm);
        double dBalanced  = lambdaMm * focalMm / (TARGET_PX_BALANCED  * pixelMm);
        double dWide      = lambdaMm * focalMm / (TARGET_PX_WIDE      * pixelMm);

        // Clamp to the effective maximum (page or explicit cap)
        dCompact  = Math.min(dCompact,  maxAperture);
        dBalanced = Math.min(dBalanced, maxAperture);
        dWide     = Math.min(dWide,     maxAperture);

        // If all are degenerate (page is very small) spread evenly in [1mm, maxAperture]
        if (dCompact < 1.0) {
            dCompact  = Math.max(1.0, maxAperture / 3.0);
            dBalanced = Math.max(1.0, maxAperture * 2.0 / 3.0);
            dWide     = Math.max(1.0, maxAperture);
        }

        // Round apertures to 0.1 mm for readability
        dCompact  = round1(dCompact);
        dBalanced = round1(dBalanced);
        dWide     = round1(dWide);

        double wavelengthNm = goal.wavelengthNm();
        double dpi = goal.dpi();

        SingleZonePlateParameters pCompact  = onAxis(dCompact,  focalMm, wavelengthNm, dpi);
        SingleZonePlateParameters pBalanced = onAxis(dBalanced, focalMm, wavelengthNm, dpi);
        SingleZonePlateParameters pWide     = onAxis(dWide,     focalMm, wavelengthNm, dpi);

        ValidationResult vCompact  = DesignValidator.validate(pCompact);
        ValidationResult vBalanced = DesignValidator.validate(pBalanced);
        ValidationResult vWide     = DesignValidator.validate(pWide);

        // --- Score each candidate ---
        double[] pixPerZone = {
                vCompact.metrics().pixelsPerOuterZone(),
                vBalanced.metrics().pixelsPerOuterZone(),
                vWide.metrics().pixelsPerOuterZone()
        };
        double[] naValues = {
                naFrom(vCompact),
                naFrom(vBalanced),
                naFrom(vWide)
        };
        double[] zoneValues = {
                vCompact.metrics().numberOfZones(),
                vBalanced.metrics().numberOfZones(),
                vWide.metrics().numberOfZones()
        };
        double[] riskValues = {
                fabricationRisk(vCompact),
                fabricationRisk(vBalanced),
                fabricationRisk(vWide)
        };

        double maxPpz   = max(pixPerZone);
        double maxNA    = max(naValues);
        double maxZones = max(zoneValues);

        double[] scores = new double[CANDIDATE_COUNT];
        for (int i = 0; i < CANDIDATE_COUNT; i++) {
            double pPrint = maxPpz   > 0 ? pixPerZone[i] / maxPpz   : 0.0;
            double pNA    = maxNA    > 0 ? naValues[i]   / maxNA    : 0.0;
            double pZones = maxZones > 0 ? zoneValues[i] / maxZones : 0.0;
            scores[i] = 0.40 * pPrint + 0.30 * pNA + 0.20 * pZones + 0.10 * riskValues[i];
        }

        // --- Build candidate objects ---
        String[] labels = {
                "Compact Zone Plate (D = " + fmt(dCompact) + " mm)",
                "Balanced Zone Plate (D = " + fmt(dBalanced) + " mm)",
                "Wide-Aperture Zone Plate (D = " + fmt(dWide) + " mm)"
        };
        SingleZonePlateParameters[] params = { pCompact, pBalanced, pWide };
        ValidationResult[] validations = { vCompact, vBalanced, vWide };

        List<CandidateDesign> unranked = new ArrayList<>();
        for (int i = 0; i < CANDIDATE_COUNT; i++) {
            List<RecommendationReason> reasons = buildReasons(
                    params[i], validations[i], scores[i],
                    pixPerZone[i], naValues[i], zoneValues[i], riskValues[i]);
            List<AssistantWarning> warnings = buildWarnings(validations[i]);
            unranked.add(new CandidateDesign(
                    labels[i], params[i], 0 /* rank assigned below */,
                    scores[i], reasons, warnings, validations[i]));
        }

        // Sort descending by composite score; ties: lower index wins (stable)
        unranked.sort(Comparator.<CandidateDesign>comparingDouble(CandidateDesign::compositeScore)
                .reversed()
                .thenComparingInt(unranked::indexOf));

        List<CandidateDesign> ranked = new ArrayList<>();
        for (int r = 0; r < unranked.size(); r++) {
            CandidateDesign c = unranked.get(r);
            ranked.add(new CandidateDesign(
                    c.label(), c.parameters(), r + 1,
                    c.compositeScore(), c.reasons(), c.warnings(), c.validation()));
        }

        List<AssistantWarning> globalWarnings = buildGlobalWarnings(goal, ranked);

        return new DesignRecommendation(
                ranked.get(0),
                ranked.subList(1, ranked.size()),
                globalWarnings);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static SingleZonePlateParameters onAxis(
            double dMm, double fMm, double wNm, double dpi) {
        return new SingleZonePlateParameters(
                dMm, fMm, wNm, dpi, 0.0, 0.0,
                MaskType.BINARY_AMPLITUDE, Polarity.POSITIVE);
    }

    private static double naFrom(ValidationResult v) {
        OpticalQualityReport qr = v.qualityReport();
        return qr != null ? qr.numericalAperture() : 0.0;
    }

    /**
     * Fabrication risk score: 1.0 if no warnings, 0.5 if any WARNING, 0.0 if any ERROR.
     */
    static double fabricationRisk(ValidationResult v) {
        boolean hasError = v.warnings().stream()
                .anyMatch(w -> w.severity() == ValidationResult.Warning.Severity.ERROR);
        if (hasError) return 0.0;
        boolean hasWarning = !v.warnings().isEmpty();
        return hasWarning ? 0.5 : 1.0;
    }

    private static List<RecommendationReason> buildReasons(
            SingleZonePlateParameters p,
            ValidationResult v,
            double compositeScore,
            double ppz, double na, double zones, double risk) {

        List<RecommendationReason> reasons = new ArrayList<>();

        // Printability
        String printDesc;
        if (ppz >= 5.0) {
            printDesc = String.format("%.1f px per outer zone — good printability at %.0f dpi", ppz, p.dpi());
        } else if (ppz >= 2.0) {
            printDesc = String.format("%.1f px per outer zone — marginal at %.0f dpi; verify printer calibration",
                    ppz, p.dpi());
        } else {
            printDesc = String.format("%.1f px per outer zone — below minimum at %.0f dpi; design is not printable",
                    ppz, p.dpi());
        }
        reasons.add(new RecommendationReason("printability", printDesc));

        // Focus quality
        OpticalQualityReport qr = v.qualityReport();
        if (qr != null) {
            reasons.add(new RecommendationReason("focus_quality",
                    String.format("NA = %.5f, Airy disk = %.0f µm, depth of focus = %.0f µm",
                            qr.numericalAperture(),
                            qr.airyDiskDiameterMicrons(),
                            qr.depthOfFocusMicrons())));
        }

        // Zone adequacy
        int nZones = v.metrics().numberOfZones();
        String zoneDesc;
        if (nZones >= 50) {
            zoneDesc = nZones + " Fresnel zones — high-quality diffraction";
        } else if (nZones >= 10) {
            zoneDesc = nZones + " Fresnel zones — adequate diffraction quality";
        } else if (nZones >= 5) {
            zoneDesc = nZones + " Fresnel zones — minimal but functional";
        } else {
            zoneDesc = nZones + " Fresnel zones — too few; focus quality will be poor";
        }
        reasons.add(new RecommendationReason("zone_adequacy", zoneDesc));

        // Fabrication risk
        String riskDesc;
        if (risk == 1.0) {
            riskDesc = "No printability warnings — low fabrication risk";
        } else if (risk == 0.5) {
            riskDesc = "Printability warnings present — moderate fabrication risk";
        } else {
            riskDesc = "Printability error — high fabrication risk; design may not reproduce";
        }
        reasons.add(new RecommendationReason("fabrication_risk", riskDesc));

        // Physical size
        reasons.add(new RecommendationReason("physical_size",
                String.format("Aperture diameter %.1f mm fits on the specified page", p.apertureDiameterMm())));

        return List.copyOf(reasons);
    }

    private static List<AssistantWarning> buildWarnings(ValidationResult v) {
        List<AssistantWarning> warnings = new ArrayList<>();
        for (ValidationResult.Warning w : v.warnings()) {
            warnings.add(new AssistantWarning(w.code(), w.message()));
        }
        return List.copyOf(warnings);
    }

    private static List<AssistantWarning> buildGlobalWarnings(
            DesignGoal goal, List<CandidateDesign> ranked) {

        List<AssistantWarning> warnings = new ArrayList<>();

        // Always include the advisory disclaimer
        warnings.add(new AssistantWarning("ADVISORY",
                "This recommendation is advisory and based on stated physical assumptions "
                + "(paraxial thin-lens approximation). Verify designs experimentally before use."));

        // Warn if even the best candidate has printability errors
        if (ranked.get(0).warnings().stream()
                .anyMatch(w -> w.code().equals("OUTER_ZONE_TOO_SMALL"))) {
            warnings.add(new AssistantWarning("ALL_CANDIDATES_CRITICAL",
                    "All candidates have outer zones smaller than 2 printer pixels at "
                    + (int) goal.dpi() + " dpi. Consider reducing the aperture or "
                    + "increasing the focal length."));
        }

        // Warn if wavelength is outside the visible range
        double wl = goal.wavelengthNm();
        if (wl < 380 || wl > 780) {
            warnings.add(new AssistantWarning("NON_VISIBLE_WAVELENGTH",
                    String.format("Wavelength %.0f nm is outside the visible range (380–780 nm). "
                            + "Ensure the material and printer are suitable.", wl)));
        }

        return List.copyOf(warnings);
    }

    private static double max(double[] arr) {
        double m = arr[0];
        for (double v : arr) if (v > m) m = v;
        return m;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
