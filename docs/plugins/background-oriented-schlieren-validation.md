# BOS target validation layers

The implementation applies validation at complementary layers:

1. JSON Schema supplies editor metadata and immediate structural constraints.
2. Bean Validation rejects malformed REST and job DTO values.
3. `BackgroundOrientedSchlierenParameters` enforces cross-field geometry and allocation bounds for every caller.
4. The renderer rejects a density that cannot satisfy the exact spacing requirement.
5. Tests compare deterministic pixel hashes and independently inspect pairwise dot spacing.

The core constructor and renderer remain authoritative; browser input attributes and JSON Schema are not treated as security boundaries.
