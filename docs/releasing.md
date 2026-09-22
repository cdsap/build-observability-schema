# Releasing the GBOS artifact

GBOS has three intentionally separate version identifiers:

| Identifier | Example | Meaning |
|---|---|---|
| Maven artifact version | `0.0.4` | Immutable distribution version for `io.github.cdsap:build-observability-schema` and `io.github.cdsap:build-observability-core` |
| GBOS schema version | `1.0.0` | Version of the JSON observation/report contract |
| Develocity key major | `gbos.v1.*` | Stable custom-value and index namespace major |

Previous artifact versions are immutable. Java 17 Gradle consumers should use
the release version configured in the root build, which publishes Java 17-compatible
Gradle Module Metadata for both artifacts.

An additive schema change can use a new Maven artifact version without changing
the schema major or Develocity key major. Removing or changing the meaning of a
contract field requires a new schema major and a corresponding new Develocity
key major. Maven Central versions are immutable; publish a new patch version
when a released artifact needs correction.

## Release process

The Maven publications follow the same local, explicit-version approach as
ProjectGenerator. The release version is configured in the root
`build.gradle.kts` and is shared by both `mavenPublishing.coordinates`
declarations; update the root version in a dedicated release-preparation change.

1. Merge the release-preparation change to `main`.
2. Configure the Central Portal credentials and signing key locally.
3. Run the fail-closed preflight with the exact version:

   ```bash
   ./scripts/preflight.sh 0.0.2
   ```

4. Publish and release directly through the Central Portal:

   ```bash
   ./gradlew publishAndReleaseToMavenCentral :core:publishAndReleaseToMavenCentral --no-configuration-cache \
     -PmavenCentralUsername="$USER_NAME" \
     -PmavenCentralPassword="$central_password" \
     -Psigning.keyId="$key_id" \
     -Psigning.password="$signing_password" \
     -Psigning.secretKeyRingFile="${GBOS_SIGNING_KEY_FILE:-$HOME/.gbos-release/secring.gpg}"
   ```

5. Wait for both Central Portal deployments to reach `PUBLISHED`. Do not tag
   before both `io.github.cdsap:build-observability-schema` and
   `io.github.cdsap:build-observability-core` are confirmed published.
6. Create and push an annotated tag matching `v<major>.<minor>.<patch>`, for
   example `v0.0.2`:

   ```bash
   git tag -a v0.0.2 -m "Release GBOS 0.0.2"
   git push origin v0.0.2
   ```

7. The tag-only release workflow builds the exact tag and creates the GitHub
   release with both Maven artifacts attached.

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
    testImplementation("io.github.cdsap:build-observability-schema:0.0.4")
    implementation("io.github.cdsap:build-observability-core:0.0.4")
}
```

The schema artifact is not a runtime dependency of producer plugins. The core
artifact is an optional runtime dependency for producers that adopt its shared
model and encoding API.
