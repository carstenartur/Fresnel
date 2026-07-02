# Troubleshooting

Map observed problems to likely causes and next checks.

| Observation | Likely cause | What to check next |
|---|---|---|
| No visible focus spot | Wrong print scaling or wrong distance range | Reprint at 100% scale and scan around expected focal length ±30% |
| Focus appears far from target value | Global print scale error | Measure calibration scale bar and compare nominal vs actual |
| Two directional/elongated foci | Non-uniform X/Y scaling or tilted plate | Check horizontal vs vertical calibration, square up plate/screen |
| Very weak contrast | Light too broadband or print density too low | Try narrow-band source (laser/green LED), increase print density |
| Strong background glow with weak spot | Zero-order leakage / polarity mismatch / low opacity | Verify mask polarity and material opacity in dark zones |
| Spot sharp in center but large halo | Normal binary-amplitude side lobes, or dirty/wrinkled film | Clean film, flatten mount, compare with expected binary behavior |
| Pattern looks jagged or merged zones | Printer cannot resolve effective zone width at chosen DPI | Use larger aperture/focal settings or higher effective printer resolution |

## Failure modes to separate

- **Print failure**: scale, density, or registration issues (check calibration
  first).
- **Optical setup failure**: alignment, distance, and source bandwidth issues.
- **Expectation mismatch**: binary masks naturally show side lobes/halo.

Record each failed attempt in the same structured format so improvements are
traceable over time:

- [Experimental workflow](../experimental-validation.md)
- [Issue #55 experimental records](https://github.com/carstenartur/Fresnel/issues/55)
