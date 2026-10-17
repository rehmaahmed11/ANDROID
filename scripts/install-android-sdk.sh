#!/usr/bin/env bash
# Provides the Android SDK pieces this project needs, then points Gradle at exactly one root.
#
# Two rules matter more than the download itself:
#  1. ANDROID_HOME and ANDROID_SDK_ROOT must resolve to the same directory. AGP aborts during
#     configuration otherwise ("Several environment variables and/or system properties contain
#     different paths to the SDK"), and GitHub's runner image pre-sets ANDROID_SDK_ROOT, so
#     overriding only ANDROID_HOME is enough to break the build.
#  2. --sdk_root must be passed to sdkmanager, otherwise it installs relative to its own
#     location and can leave the directory Gradle is looking at empty.
# Reusing the image's SDK when it already satisfies the requirement avoids a ~120 MB download
# and the transient failures that come with it.
set -euo pipefail

PLATFORM="platforms/android-34"
BUILD_TOOLS="build-tools/34.0.0"
CMDLINE_TOOLS_ZIP="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

export_github_env() {
  # GITHUB_ENV only exists on a runner; harmless when run by a human.
  if [ -n "${GITHUB_ENV:-}" ]; then
    {
      echo "ANDROID_HOME=$1"
      echo "ANDROID_SDK_ROOT=$1"
    } >>"$GITHUB_ENV"
  fi
}

usable_root() {
  local candidate="$1"
  [ -n "$candidate" ] && [ -d "$candidate/$PLATFORM" ] && [ -d "$candidate/$BUILD_TOOLS" ]
}

ROOT=""
for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /usr/local/lib/android/sdk "$HOME/android-sdk"; do
  if usable_root "$candidate"; then
    ROOT="$candidate"
    break
  fi
done

if [ -n "$ROOT" ]; then
  echo "reusing the Android SDK already present at $ROOT"
else
  ROOT="${ANDROID_HOME:-$HOME/android-sdk}"
  # Prefer a root we know we can write; fall back to home if the image's SDK dir is read-only.
  if ! mkdir -p "$ROOT" 2>/dev/null || [ ! -w "$ROOT" ]; then
    ROOT="$HOME/android-sdk"
    mkdir -p "$ROOT"
  fi
  echo "no usable SDK found; installing command-line tools into $ROOT"

  TOOLS="$ROOT/cmdline-tools/latest/bin/sdkmanager"
  if [ ! -x "$TOOLS" ]; then
    ZIP="$(mktemp -t cmdline-tools.XXXXXX).zip"
    # --retry and --http1.1: dl.google.com intermittently resets HTTP/2 streams, which aborts
    # the whole CI job with curl exit 92.
    curl -fsSL --retry 5 --retry-all-errors --retry-delay 3 --http1.1 -o "$ZIP" "$CMDLINE_TOOLS_ZIP"
    TMP="$(mktemp -d)"
    unzip -q "$ZIP" -d "$TMP"
    mkdir -p "$ROOT/cmdline-tools"
    rm -rf "$ROOT/cmdline-tools/latest"
    mv "$TMP/cmdline-tools" "$ROOT/cmdline-tools/latest"
    rm -rf "$TMP" "$ZIP"
    TOOLS="$ROOT/cmdline-tools/latest/bin/sdkmanager"
    chmod +x "$TOOLS"
  fi

  echo "accepting licenses"
  yes | "$TOOLS" --sdk_root="$ROOT" --licenses >/dev/null 2>&1 || true

  echo "installing platform-tools, platforms;android-34, build-tools;34.0.0"
  "$TOOLS" --sdk_root="$ROOT" --install \
    "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null
fi

export_github_env "$ROOT"

[ -d "$ROOT/$PLATFORM" ] || { echo "missing $PLATFORM in $ROOT" >&2; exit 1; }
[ -d "$ROOT/$BUILD_TOOLS" ] || { echo "missing $BUILD_TOOLS in $ROOT" >&2; exit 1; }
echo "sdk ready: $ROOT (ANDROID_HOME and ANDROID_SDK_ROOT both set to it)"
ls "$ROOT/platforms" "$ROOT/build-tools"
