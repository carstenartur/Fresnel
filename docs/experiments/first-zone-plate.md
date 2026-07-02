# First printable zone plate (start-to-finish)

This is the fastest complete path from parameter entry in Fresnel to a first
visible focus on a wall or paper target.

## Concrete Fresnel design configuration

Use **Zone Plate** with:

- Aperture diameter: **10.0 mm**
- Focal length: **1000.0 mm**
- Wavelength: **550.0 nm**
- DPI: **1200**
- Mask type: **BINARY_AMPLITUDE**
- Polarity: **POSITIVE**
- Target offsets X/Y: **0.0 mm / 0.0 mm**

This matches the default single-zone-plate experiment shape already used in
Fresnel validation examples (`zone-plate`, single design document).

## Materials

- Inkjet transparency film (or laser transparency compatible with your printer)
- Printer capable of stable 1200 dpi mode
- Tape and scissors/craft knife
- White card/paper screen
- Ruler or tape measure (at least 1.5 m)
- Light source: green LED flashlight, low-power laser pointer, or sunlight

## Print workflow

1. In the Zone Plate panel, export the **Calibration PDF** first and print at
   **100% / actual size** (no fit-to-page).
2. Check the 0–100 mm scale bar with a ruler.
3. Export the zone plate PNG/PDF with the configuration above.
4. Print again at 100% size on transparency film.
5. Cut out and mount flat (no wrinkles) on a simple holder.

For calibration-sheet details and interpretation, see:

- [Zone plate calibration section](../plugins/zone-plate.md#print-calibration-sheet)
- [Calibration sheets tracking issue #56](https://github.com/carstenartur/Fresnel/issues/56)

## Illumination and first visible focus

1. Place the printed plate normal to the beam/light direction.
2. Put a white screen around **1.0 m** behind the plate.
3. Move the screen slowly around 0.8–1.2 m until the smallest bright spot appears.
4. Record the best distance and spot appearance (photo if possible).

Expected observations:

- A clear bright central spot near the design focal distance.
- A faint halo/rings around the focus (normal for binary amplitude masks).
- If focus is weak, switch to narrower spectrum light (green LED or laser).

## Record the experiment

Capture setup + measured focal distance using Fresnel’s experiment comparison
workflow:

- [Experimental validation workflow docs](../experimental-validation.md)
- [Experimental records tracking issue #55](https://github.com/carstenartur/Fresnel/issues/55)

Then export JSON/Markdown from `/api/experiments/export.json` and
`/api/experiments/export.md` for reproducible lab notes.

## Next steps

- [Measuring focal length](measuring-focal-length.md)
- [Laser vs LED vs sunlight](laser-vs-led-vs-sunlight.md)
- [Printing on transparency film](printing-on-transparency-film.md)
- [Troubleshooting](troubleshooting.md)
- [Safety notes](safety.md)
