# Releasing the GBOS artifact

GBOS has three intentionally separate version identifiers:

| Identifier | Example | Meaning |
|---|---|---|
| Maven artifact version | `0.0.1` | Immutable distribution version for `io.github.cdsap:build-observability-schema` |
| GBOS schema version | `1.0.0` | Version of the JSON observation/report contract |
| Develocity key major | `gbos.v1.*` | Stable custom-value and index namespace major |

An additive schema change can use a new Maven artifact version without changing
the schema major or Develocity key major. Removing or changing the meaning of a
contract field requires a new schema major and a corresponding new Develocity
key major. Maven Central versions are immutable; publish a new patch version
when a released artifact needs correction.

## Release process

1. Merge the release changes to `main`.
2. Confirm the `io.github.cdsap` namespace is verified in Maven Central and the
   repository secrets below are configured.
3. Create and push an annotated tag matching `v<major>.<minor>.<patch>`, for
   example `v0.0.1`:

   ```bash
   git tag -a v0.0.1 -m "Release GBOS 0.0.1"
   git push origin v0.0.1
   ```

4. The tag-only release workflow builds the exact tag, publishes it through the
   Central Portal's Gradle-compatible staging API, transfers the staging
   repository to the Portal API, and polls until the deployment is
   `PUBLISHED`.
5. The workflow then verifies the POM, main JAR, sources JAR, and Javadoc JAR
   from Maven Central before succeeding.

The workflow does not run for pull requests or ordinary branch pushes. It fails
before publication when the tag is not a release version, required credentials
are absent, or the version already exists on Maven Central.

## Required GitHub Actions secrets

- `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`: Central Portal user-token credentials.
- `MAVEN_CENTRAL_GPG_PRIVATE_KEY`: ASCII-armored private signing key.
- `MAVEN_CENTRAL_GPG_PASSWORD`: passphrase for that key.

The credentials are used only by the tag-triggered workflow. Producer projects
should consume released versions as a test/CI dependency:

```kotlin
dependencies {
    testImplementation("io.github.cdsap:build-observability-schema:0.0.1")
}
```

The artifact is not a runtime dependency of producer plugins.
