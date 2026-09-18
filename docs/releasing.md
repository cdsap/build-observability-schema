# Releasing the GBOS artifact

GBOS has three intentionally separate version identifiers:

| Identifier | Example | Meaning |
|---|---|---|
| Maven artifact version | `0.0.2` | Immutable distribution version for `io.github.cdsap:build-observability-schema` |
| GBOS schema version | `1.0.0` | Version of the JSON observation/report contract |
| Develocity key major | `gbos.v1.*` | Stable custom-value and index namespace major |

`0.0.1` is already published and immutable. It remains available for consumers
that explicitly need it, but Java 17 Gradle consumers should use `0.0.2`, which
publishes Java 17-compatible Gradle Module Metadata.

An additive schema change can use a new Maven artifact version without changing
the schema major or Develocity key major. Removing or changing the meaning of a
contract field requires a new schema major and a corresponding new Develocity
key major. Maven Central versions are immutable; publish a new patch version
when a released artifact needs correction.

## Release process

The Maven publication follows the same local, explicit-version approach as
ProjectGenerator. The release version is configured in `build.gradle.kts` in
both `version` and `mavenPublishing.coordinates`; update both in a dedicated
release-preparation change.

1. Merge the release-preparation change to `main`.
2. Configure the Central Portal credentials and signing key locally.
3. Run the fail-closed preflight with the exact version:

   ```bash
   ./scripts/preflight.sh 0.0.2
   ```

4. Publish and release directly through the Central Portal:

   ```bash
   ./gradlew publishAndReleaseToMavenCentral --no-configuration-cache \
     -PmavenCentralUsername="$USER_NAME" \
     -PmavenCentralPassword="$central_password" \
     -Psigning.keyId="$key_id" \
     -Psigning.password="$signing_password" \
     -Psigning.secretKeyRingFile="${GBOS_SIGNING_KEY_FILE:-$HOME/.gbos-release/secring.gpg}"
   ```

5. Wait for the Central Portal deployment to reach `PUBLISHED`. Do not tag
   before that state is confirmed.
6. Create and push an annotated tag matching `v<major>.<minor>.<patch>`, for
   example `v0.0.2`:

   ```bash
   git tag -a v0.0.2 -m "Release GBOS 0.0.2"
   git push origin v0.0.2
   ```

7. The tag-only release workflow builds the exact tag and creates the GitHub
   release with the main artifact attached.

The workflow does not publish to Maven Central and does not run for pull
requests or ordinary branch pushes. `publishToMavenLocal` remains available for
consumer development; it uses the same signing properties when signatures are
enabled.

## Local release credentials

The preflight reads `~/.gbos-release/env` by default. Set `GBOS_RELEASE_ENV` to
use another file. It requires these names, matching ProjectGenerator:

- `USER_NAME`: Central Portal user-token username.
- `central_password`: Central Portal user-token password.
- `key_id`: signing key ID.
- `signing_password`: signing key passphrase.

The signing key is read from `~/.gbos-release/secring.gpg` by default. Set
`GBOS_SIGNING_KEY_FILE` to use another file. Keep both files outside the
repository and never commit their contents.

Producer projects should consume released versions as a test/CI dependency:

```kotlin
dependencies {
    testImplementation("io.github.cdsap:build-observability-schema:0.0.2")
}
```

The artifact is not a runtime dependency of producer plugins.
