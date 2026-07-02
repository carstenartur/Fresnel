# Measuring focal length

Use this after the first successful focus to measure how close the printed optic
is to the design target.

## Procedure

1. Fix zone plate and light source so they do not move during measurement.
2. Move a white screen along the optical axis and find the smallest spot.
3. Measure distance from zone-plate plane to the screen plane.
4. Repeat at least 3 times and average.

## Practical tips

- Mark the optic center and measure from the same physical reference each run.
- Keep the plate perpendicular to the beam to avoid apparent focal shift.
- Prefer dim ambient light so the spot edge is visible.

## Compare with design value

If design focal length is `f_design` and measured is `f_meas`:

- Absolute error: `f_meas - f_design`
- Relative error: `(f_meas - f_design) / f_design`

Commonly acceptable first-pass result for home printing is roughly ±2–5%,
depending on printer scale accuracy and illumination bandwidth.

## Save records

Use the Fresnel experimental validation/export flow so the measurement remains
reproducible over time:

- [Experimental validation workflow](../experimental-validation.md)
- [Tracking issue #55 (experimental records)](https://github.com/carstenartur/Fresnel/issues/55)
