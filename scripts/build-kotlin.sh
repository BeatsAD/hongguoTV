#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -z "${JAVA_HOME:-}" ]]; then
  for native_jdk in "$HOME"/.cache/hongguotv/toolchain/jdk-17*/Contents/Home; do
    if [[ -x "$native_jdk/bin/java" ]]; then export JAVA_HOME="$native_jdk"; break; fi
  done
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/.cache/hongguotv/android-sdk" ]]; then
  export ANDROID_HOME="$HOME/.cache/hongguotv/android-sdk"
fi
# Allocate the hidden build number automatically; this command never publishes.
version_env="$(python3 "$repo_dir/scripts/tv-update.py" version)"
while IFS='=' read -r name value; do
  case "$name" in
    HONGGUOTV_VERSION_CODE) [[ "$value" =~ ^[0-9]+$ ]] || exit 1; export HONGGUOTV_VERSION_CODE="$value" ;;
    HONGGUOTV_VERSION_NAME) [[ "$value" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || exit 1; export HONGGUOTV_VERSION_NAME="$value" ;;
    *) exit 1 ;;
  esac
done <<< "$version_env"
[[ ! -e "$repo_dir/outputs/releases/v$HONGGUOTV_VERSION_NAME" ]] || { echo "This version already has local output; keep it and use a new version." >&2; exit 1; }
"$repo_dir/kotlin-tv/gradlew" -p "$repo_dir/kotlin-tv" :core:test :app:testDebugUnitTest :app:lintDistribution :app:assembleDistribution --console=plain
python3 "$repo_dir/scripts/tv-update.py" prepare --apk "$repo_dir/kotlin-tv/app/build/outputs/apk/distribution/app-distribution.apk"
