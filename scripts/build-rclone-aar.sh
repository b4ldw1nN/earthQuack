#!/usr/bin/env bash
# Build the rclone gomobile AAR and wire it into EarthQuack.
#
# This is the one build step that is not Gradle. It is kept as a script so
# that a clean checkout has a single command to run, and so the exact
# toolchain requirements are stated in one place instead of being folklore.
#
# Usage:
#   ./scripts/build-rclone-aar.sh              # arm64 only (fast, matches dev devices)
#   ./scripts/build-rclone-aar.sh --all-abis   # arm64 + armv7 + x86_64 (release)
#
# Requirements:
#   - Go 1.26 or newer (rclone v1.75.x requires go 1.26)
#   - Android NDK, e.g. /opt/android-ndk
#   - Android SDK with an API 26+ platform installed
#   - gomobile:  go install golang.org/x/mobile/cmd/gomobile@latest
#
# Output:
#   app/libs/rclone.aar   (git-ignored; contains jni/<abi>/libgojni.so)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SHIM_DIR="$REPO_ROOT/rclone-android"
OUT_AAR="$REPO_ROOT/app/libs/rclone.aar"

ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-/opt/android-ndk}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_NDK_HOME ANDROID_HOME
export PATH="$PATH:$HOME/go/bin:$(go env GOPATH)/bin"

# -androidapi must be <= the app's minSdk. The app declares minSdk 26.
ANDROID_API=26
JAVAPKG="com.example.earthquack"

die() { echo "error: $*" >&2; exit 1; }

command -v go     >/dev/null || die "go not found on PATH"
command -v gomobile >/dev/null || die "gomobile not found. Install with:
    go install golang.org/x/mobile/cmd/gomobile@latest"
[ -d "$ANDROID_NDK_HOME" ] || die "ANDROID_NDK_HOME=$ANDROID_NDK_HOME does not exist.
    gomobile bind -target=android requires a local NDK."

if [ "${1:-}" = "--all-abis" ]; then
    TARGETS="android/arm64,android/arm,android/amd64"
    echo "==> Building all ABIs: $TARGETS"
else
    TARGETS="android/arm64"
    echo "==> Building arm64 only (pass --all-abis for a release build)"
fi

echo "==> Resolving Go dependencies"
cd "$SHIM_DIR"

# Deliberately `go build -mod=mod`, NOT `go mod tidy`.
#
# `go mod tidy` walks the *test* dependencies of every imported package, and
# rclone's own fs/operations and fs/sync test files import
# `_ "github.com/rclone/rclone/backend/all"`. So tidy tries to resolve every
# rclone backend ever written — ProtonMail, Oracle OCI, Cloudflare, Borg and
# the rest: hundreds of megabytes of downloads for code that is never compiled
# into the AAR.
#
# `go build -mod=mod` resolves only what is actually imported, which is all a
# gomobile build needs. Seconds instead of hanging on the module proxy.
go build -mod=mod ./rclone/

# gomobile requires the x/mobile tool dependency to be present in the module.
go get -tool golang.org/x/mobile/cmd/gobind

echo "==> gomobile bind"
mkdir -p "$(dirname "$OUT_AAR")"
rm -f "$OUT_AAR"

for target in ${TARGETS//,/ }; do
    # gomobile takes one -target per invocation; -o appends the ABI name.
    abi_out="$OUT_AAR"
    case "$target" in
        android/arm)   abi_out="$OUT_AAR" ;;
        android/amd64) abi_out="$OUT_AAR" ;;
    esac
    echo "    -> $target"
    gomobile bind \
        -target="$target" \
        -androidapi="$ANDROID_API" \
        -javapkg="$JAVAPKG" \
        -o "$abi_out" \
        ./rclone
done

echo "==> Built $OUT_AAR"
ls -lh "$OUT_AAR"

cat <<EOF

Done. Now build the app:

    ./gradlew :app:assembleDebug

The generated Java API is:

    com.example.earthquack.rclone.Rclone.initialize()
    com.example.earthquack.rclone.Rclone.rpc(method, input) -> RcloneResult
    com.example.earthquack.rclone.Rclone.shutdown()

wrapped by com.example.earthquack.storage.RcloneEngine.

Note: if you built more than one ABI, update the abiFilters list in
app/build.gradle.kts to match, or the APK will silently ship only one.
EOF
