# BOS target review guide

Reviewers should verify the implemented boundary rather than assuming a complete BOS measurement system:

- registry kind is `MEASUREMENT`;
- only target-generation and PNG capabilities are advertised;
- exact spacing is preserved or generation fails;
- target allocation and placement work are bounded;
- preview reduction cannot affect the authoritative export;
- seed and all geometry values participate in canonical job provenance;
- no capture or analysis state is fabricated.
