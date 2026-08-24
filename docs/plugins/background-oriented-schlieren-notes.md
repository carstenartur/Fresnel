# BOS target design notes

The renderer uses `SplittableRandom` solely to derive a stable pseudorandom placement sequence from the public seed. Candidate centers are checked against a spatial grid, so minimum-distance checks remain local instead of comparing every candidate with every previously placed dot.

Dots are rendered without antialiasing into a binary image. This avoids hidden greyscale edge semantics in the authoritative target. The preview may be downscaled with nearest-neighbour interpolation for browser display; the export endpoint always returns the full raster.
