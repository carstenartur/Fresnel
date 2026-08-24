# BOS target reproducibility

A BOS target is reproducible when these values are fixed:

- normalized parameter schema version;
- algorithm version;
- width and height in pixels;
- seed;
- dot diameter and requested fill ratio;
- minimum spacing;
- border/fiducial settings;
- polarity.

`intendedDpi` records intended physical use but does not alter the pixel raster. The authoritative export is therefore independent of printer-driver defaults. A later durable experiment manifest will store both the normalized parameter SHA-256 and the generated PNG SHA-256 so a capture can be tied to the exact displayed or printed target.
