#!/bin/bash
# Builds a static OpenSSL libcrypto for each Android ABI into third_party/openssl/<abi>.
# Run once before the first Gradle build. Needs the NDK (ANDROID_NDK_ROOT) and perl.
set -euo pipefail

OPENSSL_VERSION=3.0.15
REPO=$(cd "$(dirname "$0")/.." && pwd)
WORK=${WORK:-$REPO/build/openssl-src}
: "${ANDROID_NDK_ROOT:?set ANDROID_NDK_ROOT to your NDK, e.g. \$ANDROID_HOME/ndk/27.2.12479018}"

HOST_TAG=$(ls "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/" | head -1)
export PATH=$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/bin:$PATH

mkdir -p "$WORK"
if [ ! -d "$WORK/openssl-$OPENSSL_VERSION" ]; then
  curl -fsSL "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz" \
    | tar xz -C "$WORK"
fi

for pair in "arm64-v8a android-arm64" "armeabi-v7a android-arm"; do
  set -- $pair
  OUT=$REPO/third_party/openssl/$1
  B=$WORK/build-$1
  rm -rf "$B" && cp -R "$WORK/openssl-$OPENSSL_VERSION" "$B" && cd "$B"
  ./Configure "$2" -D__ANDROID_API__=24 no-shared no-tests no-engine no-ui-console --prefix="$OUT" --libdir=lib
  make -j8 build_libs
  make install_dev
done
