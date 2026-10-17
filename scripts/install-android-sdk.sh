#!/usr/bin/env bash
# Installs just enough of the Android SDK for a command-line build of this project, into a
# single, explicit root. Used by CI; also usable on a dev machine.
#
# The --sdk_root flag is not decoration: sdkmanager otherwise installs relative to its own
# location, and AGP refuses to build at all when ANDROID_HOME and ANDROID_SDK_ROOT point at
# different directories ("Several environment variables and/or system properties contain
# different paths to the SDK"). Pinning one root and exporting it both ways keeps that true.
set -euo pipefail

: "${ANDROID_HOME:=$HOME/android-sdk}"
SDK_ROOT="$ANDROID_HOME"
export ANDROID_HOME ANDROID_SDK_ROOT="$SDK_ROOT"
mkdir -p "$SDK_ROOT"

TOOLS="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$TOOLS" ]; then
  echo "downloading command-line tools into $SDK_ROOT"
  # Pinned revision: the "*_latest" alias changes contents without notice and silently
  # changes the build.
  ZIP="$(mktemp -t cmdline-tools.XXXXXX).zip"
  curl -fsSLo "$ZIP" https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  TMP="$(mktemp -d)"
  unzip -q "$ZIP" -d "$TMP"
  mkdir -p "$SDK_ROOT/cmdline-tools"
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mv "$TMP/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
  rm -rf "$TMP" "$ZIP"
  chmod +x "$TOOLS"
fi

echo "accepting licenses"
yes | "$TOOLS" --sdk_root="$SDK_ROOT" --licenses >/dev/null 2>&1 || true

echo "installing platform-tools, platforms;android-34, build-tools;34.0.0"
"$TOOLS" --sdk_root="$SDK_ROOT" --install \
  "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null

echo "sdk ready: $SDK_ROOT"
ls "$SDK_ROOT/platforms" "$SDK_ROOT/build-tools"
