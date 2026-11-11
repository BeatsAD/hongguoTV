# deepmerge local security patch

This is the CommonJS distribution and TypeScript declarations from npm `deepmerge@4.3.1`, with its original MIT license retained.

- Upstream: https://github.com/TehShrike/deepmerge
- Advisory: https://github.com/advisories/GHSA-gcqx-w48m-w2h4
- Original CommonJS SHA-256: `2e5160f84a1141bd927264afe4d15766f1e6cf07daef182b37ff53fea121de7d`
- Local version: `4.3.1-hongguo.1`; this is **not** an official upstream release.

The shared key enumerator drops `__proto__` before either the target-copy or source-merge loop reads or assigns it. This protects returned objects even when the target has a null prototype, contains an own prototype key, or nests such values. All other upstream merge behavior, options, symbols, arrays, exports and declarations are retained.

The root declares this package as a local development dependency; the `$deepmerge` override makes all legacy React Native CLI consumers resolve that same installed package. Using a bare relative directory override for transitive dependencies is intentionally avoided because npm can resolve it relative to the consumer instead of the repository root. It does not participate in the Kotlin TV application or standalone Node service.

Regression coverage is in `tests/dependency-security.test.mjs`, including source and target keys, escaped keys, nested objects, getter side effects, normal arrays, symbols and the actual CLI configuration merge helper. Replace the local patch with an official fixed release when one becomes available.
