# BOS target safety limits

The random-dot target generator treats its input as untrusted data.

- Width and height are limited to 256–4096 pixels.
- Total raster area is limited to 12,000,000 pixels before image allocation.
- Dot count is limited to 500,000.
- Dot diameter, spacing, fill ratio, border and DPI are bounded.
- Placement attempts are bounded and infeasible combinations fail explicitly.
- Output is a binary PNG generated in memory.
- Parameters cannot contain paths, URLs, callback addresses, commands or executable module names.
- The pattern seed affects deterministic placement only; it does not initialize a cryptographic generator and is not secret material.

These limits are part of the core parameter constructor, not merely browser hints or Bean Validation annotations. Consequently direct Java calls, REST requests and `.fresnel` job files are subject to the same allocation and geometry checks.
