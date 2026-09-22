#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 1 || ! "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Usage: $0 <major.minor.patch>" >&2
  exit 2
fi

version="$1"
root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root_dir"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "Release preflight requires a clean working tree" >&2
  exit 1
fi

if ! git remote get-url origin >/dev/null 2>&1; then
  echo "Release preflight requires an origin remote" >&2
  exit 1
fi

release_env="${GBOS_RELEASE_ENV:-$HOME/.gbos-release/env}"
if [[ -f "$release_env" ]]; then
  # shellcheck disable=SC1090
  source "$release_env"
fi

for variable in USER_NAME central_password key_id signing_password; do
  if [[ -z "${!variable:-}" ]]; then
    echo "$variable is required; configure it in $release_env or the environment" >&2
    exit 1
  fi
done

signing_key_file="${GBOS_SIGNING_KEY_FILE:-$HOME/.gbos-release/secring.gpg}"
if [[ ! -f "$signing_key_file" ]]; then
  echo "Signing key file does not exist: $signing_key_file" >&2
  exit 1
fi

if ! command -v gh >/dev/null 2>&1 || ! gh auth status >/dev/null 2>&1; then
  echo "GitHub CLI authentication is required for release preflight" >&2
  exit 1
fi

if ! grep -Fq "version = \"$version\"" build.gradle.kts || \
   ! grep -Fq "coordinates(\"io.github.cdsap\", \"build-observability-schema\", \"$version\")" build.gradle.kts; then
  echo "build.gradle.kts must configure release version $version in both version and coordinates" >&2
  exit 1
fi

tag="v$version"
if git rev-parse --verify --quiet "refs/tags/$tag" >/dev/null || \
   git ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null 2>&1; then
  echo "Release tag already exists: $tag" >&2
  exit 1
fi

java_major="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)"
if [[ -z "$java_major" || "$java_major" -lt 17 ]]; then
  echo "Java 17 or newer is required for release preflight" >&2
  exit 1
fi

./gradlew clean verifyArtifactLayout verifyJavaTargetMetadata jar :core:build :core:jar

echo "Release preflight passed for $tag"
