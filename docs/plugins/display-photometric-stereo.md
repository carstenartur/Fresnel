# Display photometric stereo

Use **Surface scanner** to measure the orientation and relative diffuse albedo of a small stationary object. The manual workflow requires a monitor and camera. Start with matte paper embossing or a small unglazed object. Reduce room light and disable monitor auto-brightness, night modes, HDR and dynamic contrast. Fix the camera next to the screen, aim at the object, and lock framing, focus, white balance and exposure.

1. Measure the lit width and height of the monitor and the distance from its plane to the object center. Camera offsets are measured looking at the screen, positive right and up. Use an upright, unmirrored image. The camera and display are assumed to be in the same plane.
2. Use the screen's native pixel dimensions. Prepare the sequence and download its manifest. The default contains a dark frame, a flat frame and eight directional discs. Four lights provide little redundancy.
3. Select a pattern and show it full screen. Wait for the image to settle, take the exposure, then press Esc. Repeat without disturbing the subject. Map each JPEG or PNG to its specific pattern ID. Entering and leaving full-screen presentation never triggers a shutter.
4. Reconstruct and select a result layer. Move a pointer over the relighting view or use the directional sliders. Download the displayed PNG or the numeric result grid with its light settings, source-file hashes and display manifest.

## Measurement model

Patterns use binary discs of equal physical diameter. Their centers lie at 40% of the screen width and height from center on an ellipse. Pixel hashes follow Photographer capture v1: the NUL-terminated FRESNEL-PATTERN-RGB8-V1 prefix, big-endian raster dimensions and the row-major sRGB triples of staged pixels. Directions point from the object to the disc center in camera coordinates (image right, image up, toward the camera). Relative irradiance accounts for inverse-square distance and the emitter cosine. The manifest gives the 2-norm condition number of the intensity-weighted light matrix. A value above 25 rejects the layout before capture.

The browser processes one source at a time, normalizes camera orientation and resamples to a maximum side of 512 pixels. It applies the selected power-law gamma separately to RGB, calculates linear luminance and subtracts the dark exposure. Flat-minus-dark provides a foreground illumination mask. Camera gamma is an approximation for processed JPEGs. Use identical processing across frames. Geometry and brightness are assumed uniform over a compact object. This version requires a fixed camera/object arrangement.

The solver fits a three-component Lambertian vector using the known light directions and irradiances. It excludes clipped and shadow observations and can reject one isolated outlier if that greatly improves agreement of the other lights. Pixels require at least four independent usable observations and a relative RMS residual at most 0.12. The normal is the unit vector of the fit and albedo is its length. Confidence combines the fraction of retained lights with the relative residual. Unsupported pixels have a zero validity bit. The residual PNG uses ten times linear RMS for visibility. The JSON preserves the numeric values and includes the thresholds.

## Files and units

The exported geometry job can be reopened with **Open job**. A PNG production output with `options.patternId` set to a manifest ID exports the corresponding lossless illumination pattern. The result JSON contains the ordered source hashes, pixel dimensions, pattern hashes, light vectors, coordinate system, solve thresholds and result arrays. Relative albedo depends on camera exposure and screen brightness. This workflow yields surface orientation and relative reflectance, without calibrated length or height measurements.
