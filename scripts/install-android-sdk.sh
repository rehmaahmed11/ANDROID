#!/usr/bin/env bash
# Installs just enough of the Android SDK for a command-line build of this project.
# Used by CI; also usable on a dev machine. Assumes `unzip`, `curl` and a writable ANDROID_HOME.
set -euo pipefail

: "${ANDROID_HOME:=$HOME/android-sdk}"
export ANDROID_HOME
mkdir -p "$ANDROID_HOME"

CMDLINE_TOOLS="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$CMDLINE_TOOLS" ] && command -v sdkmanager >/dev/null 2>&1; then
  echo "using sdkmanager already on PATH: $(command -v sdkmanager)"
  CMDLINE_TOOLS="$(command -v sdkmanager)"
fi

if [ ! -x "$CMDLINE_TOOLS" ]; then
  echo "downloading command-line tools into $ANDROID_HOME"
  # Versioned URL from dl.google.com; the "_latest" alias is not stable across revisions.
  TOOLS_ZIP="$(mktemp -t cmdline-tools.XXXXXX).zip"
  curl -fsSLo "$TOOLS_ZIP" \
    https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  TMP_DIR="$(mktemp -d)"
  unzip -q "$TOOLS_ZIP" -d "$TMP_DIR"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$TMP_DIR/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$TMP_DIR" "$TOOLS_ZIP"
  CMDLINE_TOOLS="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
fi

echo "accepting licenses"
yes | "$CMDLINE_TOOLS" --licenses >/dev/null 2>&1 || true

echo "installing platform-tools, platforms;android-34, build-tools;34.0.0"
"$CMDLINE_TOOLS" --install "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null

echo "sdk ready: $ANDROID_HOME"
ls "$ANDROID_HOME/platforms" "$ANDROID_HOME/build-tools"
