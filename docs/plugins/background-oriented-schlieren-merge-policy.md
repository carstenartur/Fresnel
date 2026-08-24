# BOS target merge policy

Merge only after the complete Maven verification succeeds and all review threads are resolved. A green focused optics-core test is not sufficient because the slice also changes plugin metadata, schema loading, canonical job validation, the React editor and browser downloads.
