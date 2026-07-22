# Android dependency trust

The Android build uses Gradle dependency locking and strict SHA-256 dependency
verification. `app/gradle.lockfile` pins the resolved module versions for every
configuration exercised by the debug/test/lint and release graphs, while
`gradle/verification-metadata.xml` authenticates the exact artifact and metadata
bytes, including build plugins.

The reviewed least-privilege graph currently contains 345 locked dependency entries
(344 modules plus Gradle's empty-configuration sentinel) across the resolved
configurations and 585 verified components / 956 artifacts. Every artifact has
exactly one SHA-256. The expected file hashes are:

- `app/gradle.lockfile`: `DAE2B81B1EBB5FEF7DFFB20BD838648E2B068A578AEE81A239BCA140E131AEEB`
- `gradle/verification-metadata.xml`: `A078F6BC9E01B0F02009C3483F0662ACBC19E5A207FDDCDA1D8BED4D5FE65A61`

Normal builds must not use `--write-locks` or `--write-verification-metadata`. CI
passes `--dependency-verification=strict` explicitly. Gradle's strict verification
mode is also the default whenever verification metadata is present.

## Intentional regeneration

Regenerate both files only after reviewing an intentional dependency or build-plugin
change, from a clean trusted network and repository state:

```bash
./gradlew testDebugUnitTest lintRelease assembleDebug --refresh-dependencies \
  --write-locks --write-verification-metadata sha256 \
  --no-parallel --no-configuration-cache --no-daemon
```

Review every changed component, version, artifact name, and checksum before commit.
The refresh is required so Gradle authenticates repository metadata again instead of
silently reusing already-parsed descriptors from a local cache.
Regenerate from an empty metadata file when dependencies were removed; Gradle appends
new checksums but does not prune obsolete components from an existing file.
Then run the same graph without either write flag and with
`--dependency-verification=strict`; repeat with `--offline` to prove the checked-in
locks and metadata are sufficient. Never add blanket trusted-artifact patterns,
disable metadata verification, or switch CI to lenient/off verification.
