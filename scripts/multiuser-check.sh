#!/usr/bin/env bash
# Reads the multi-user policy of a connected Android phone over adb and prints tagged output
# for the app's Diagnostics screen. Read-only: every command here is a query, never a change.
#
#   ./scripts/multiuser-check.sh > personas-device.txt
# then paste personas-device.txt into Personas ▸ Device analysis ▸ section 2.
set -euo pipefail

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found. Install Android platform-tools (https://developer.android.com/tools/releases/platform-tools)" >&2
  exit 1
fi

echo "# Personas device probe - $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "# serial: $(adb get-serial 2>/dev/null || echo none)"

adb shell 'echo @model; getprop ro.product.model; \
echo @release; getprop ro.build.version.release; \
echo @sdk; getprop ro.build.version.sdk; \
echo @buildtype; getprop ro.build.type; \
echo @incremental; getprop ro.build.version.incremental; \
echo @manufacturer; getprop ro.product.manufacturer; \
echo @fingerprint; getprop ro.build.fingerprint; \
echo @maxusers; pm get-max-users | tail -n 1; \
echo @maxusersprop; getprop fw.max_users; \
echo @multiui; getprop fw.show_multiuserui; \
echo @hiddenusers; getprop fw.show_hidden_users; \
echo @managed; pm list features | grep -c managed_users; \
echo @userlines; pm list users | grep -c "UserInfo{"; \
echo @users; pm list users'

# Never fatal, and never changes anything: these are the ones that some OEM builds reject.
adb shell 'am get-current-user' 2>/dev/null | sed 's/^/@currentuser /' || true

{
  echo "# device-side settings provider values that gate the Users screens"
  adb shell 'settings get global device_name' 2>/dev/null | sed 's/^/@devicename /' || true
} || true

echo "# done"
