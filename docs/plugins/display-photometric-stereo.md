# Display photometric stereo

Use **Surface scanner** to measure the orientation and relative diffuse albedo of a small stationary object. The manual workflow requires a monitor and camera. Start with matte paper embossing or a small unglazed object. Reduce room light and disable monitor auto-brightness, night modes, HDR and dynamic contrast. Fix the camera next to the screen, aim at the object, and lock framing, focus, white balance and exposure.

1. Measure the lit width and height of the monitor and the distance from its plane to the object center. Camera offsets are measured looking at the screen, positive right and up. Use an upright, unmirrored image. The camera and display are assumed to be in the same plane.
2. Use the screen's native pixel dimensions. Prepare the sequence and download its manifest. The default contains a dark frame, a flat frame and eight directional discs. Four lights provide little redundancy.
3. Select a pattern and show it full screen. Wait for the image to settle, take the exposure, then press Esc. Repeat without disturbing the subject. Map each JPEG or PNG to its specific pattern ID. Entering and leaving full-screen presentation never triggers a shutter.
4. Reconstruct and select a result layer. Move a pointer over the relighting view or use the directional sliders. Download the displayed PNG or the numeric result grid with its light settings, source-file hashes and display manifest.

## Measurement model

Patterns use binary discs of equal physical diameter. Their centers lie at 40% of the screen width and height from center on an ellipse. Pixel hashes follow Photographer capture v1: the NUL-terminated FRESNEL-PATTERN-RGB8-V1 prefix, big-endian raster dimensions and the row-major sRGB triples of staged pixels. Directions point from the object to the disc center in camera coordinates (image right, image up, toward the camera). Relative irradiance accounts for inverse-square distance and the emitter cosine. The manifest gives the 2-norm condition number of the intensity-weighted light matrix. A value above 25 rejects the layout before capture.

The browser processes one source at a time, normalizes camera orientation and resamples to a maximum side of 512 pixels. It applies the selected power-law gamma separately to RGB, calculates linear luminance and subtracts the dark exposure. Flat-minus-dark provides a foreground illumination mask. Camera gamma is an approximation for processed JPEGs. Use identical processing across frames. Geometry and brightness are assumed uniform over a compact object. Keep the object stationary. The pipeline compares centered image-gradient magnitudes against the flat frame to estimate small camera translations at the analysis resolution. Confident shifts of up to three pixels are corrected; newly exposed borders are excluded. Larger shifts, poorly correlated images or disagreement between textured regions reject the frame. A low-texture frame has no observable displacement and retains identity alignment with a warning. Each source records the transform, correlation and clipped fraction. The quality summary flags strong ambient light and widespread directional clipping.

The solver fits a three-component Lambertian vector using the known light directions and irradiances. It excludes clipped and shadow observations and can reject one isolated outlier if that greatly improves agreement of the other lights. Pixels require at least four independent usable observations and a relative RMS residual at most 0.12. The normal is the unit vector of the fit and albedo is its length. Confidence combines the fraction of retained lights with the relative residual. Unsupported pixels have a zero validity bit. The residual PNG uses ten times linear RMS for visibility. The JSON preserves the numeric values and includes the thresholds.

## Files and units

The exported geometry job can be reopened with **Open job**. A PNG production output with `options.patternId` set to a manifest ID exports the corresponding lossless illumination pattern. The result JSON contains the ordered source hashes, pixel dimensions, pattern hashes, light vectors, coordinate system, solve thresholds and result arrays. Relative albedo depends on camera exposure and screen brightness. Open **Saved experiments** and sign in to save mapped sources and the current result to the server. A checksummed experiment bundle can be downloaded and imported there. Restored camera files can be reconstructed again. The archive stores numeric grids as base64 little-endian Float32 and unsigned 8-bit arrays.

## Relative relief and export scale

Choose **Integrate relative height** to solve a masked Poisson least-squares problem from the measured normal gradients. The solver excludes low-confidence and grazing pixels, calculates the circulation around each complete four-pixel cell and rejects excessive curl or residual. It assigns independent zero datums to disconnected regions and leaves holes empty. Optionally subtract a fitted plane per component. The result grid contains the height method, curl, residual, mask and relative values. The PNG stretches supported heights over the grayscale range.

An accepted result with a nonzero height range can be exported as a closed STL relief. Supply the model width, desired height span and base thickness. All three dimensions are user-assigned millimeters. The file header records the assigned scale. The experiment does not measure physical height or length.

## Synchronized Photographer capture

Pair Photographer in the camera provider controls. Prepare the illumination sequence, open **Synchronized Photographer capture**, sign in to Fresnel, and select a camera. Exposure and focus are locked by the provider when available. Otherwise lock them on the camera and confirm before preparing the capture.

**Run camera sequence full screen** stages and checks each pattern on the native display. It waits two animation frames and the specified settling time, then triggers the corresponding remote step once. Fresnel accepts the returned shot only while the same presentation remains visible. Leaving full screen, hiding the tab or changing the display mode interrupts the sequence and requests cancellation. A previously accepted step remains available in the private source archive. Reopen a ready session to continue at its next expected pattern. After an interrupted pending exposure, cancel that session before beginning another sequence. **Open captured exposures** verifies the stored media checksums and loads the accepted files for reconstruction.

## Setup checks and object region

The setup drawing shows an upright camera above the display. Camera placement presets fill in offsets for the selected screen size; adjust them to match the actual mount. Save a named display profile in the browser to reuse its physical size and native raster. Saved experiment parameters also record the resolved display values and their SHA-256 identity.

Open **Test exposures and object region** after preparing the sequence. Map the flat exposure first to inspect a bounded, upright camera preview. Add the dark frame and two opposite directional patches for an ambient-to-flat ratio and directional response check. Results identify clipping, low signal, movement and limited texture. Flat, textureless material may have a low-contrast edge metric even when focus is correct; inspect the preview visually.

Drag across the preview or set the four percentage boundaries to select a rectangular region. The solve retains the source grid dimensions and masks pixels outside this region. Saved experiments restore that selection, and the result records its normalized bounds. A geometry job can also be used independently of camera media.

Select **Camera exposure** in the result layers to compare the original mapped frame with the measured relighting. Relit PNGs carry the sequence identity, region and numeric light position, intensity and ambient level in the PNG text metadata.


## Optional physical Canon run

Fix the camera on a tripod beside or above the display. With a matte test object
facing the display, set manual exposure, ISO, focus and white balance. Measure
the screen dimensions and the offsets from its center to the lens. Start with
the flat exposure and opposite directional patches. Adjust exposure until the
setup inspector shows detail on the brightest patch and a usable response on
both sides of the object.

Use the ordinary mapped-files workflow for camera JPEGs. For a paired camera,
select the device in Photographer, confirm the connection in Fresnel and run
the synchronized sequence if the adapter advertises the required capture and
media capabilities. Record the physical setup and retain the originals when
comparing repeated runs. The automated acceptance suite uses a mock camera.
[Photographer provider guide](../integrations/photographer-provider.md)
