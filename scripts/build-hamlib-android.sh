#!/usr/bin/env bash
set -euo pipefail
# Hamlib 4.7.2, immutable source pin. Build in ignored directories, never the source tree.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHA=40f63488fe0bd751b147f48d62fd217bf53713a0
WORK="$ROOT/build/hamlib"
SOURCE="$WORK/source"
mkdir -p "$WORK"
if [[ ! -f "$SOURCE/configure" ]]; then
  git clone --filter=blob:none https://github.com/Hamlib/Hamlib.git "$SOURCE"
  git -C "$SOURCE" checkout --detach "$SHA"
  (cd "$SOURCE" && ./bootstrap)
fi
[[ "$(git -C "$SOURCE" rev-parse HEAD)" == "$SHA" ]]
NDK="${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
for ABI in arm64-v8a armeabi-v7a; do
  case "$ABI" in
    arm64-v8a) TARGET=aarch64-linux-android; HOST=aarch64-linux-android ;;
    armeabi-v7a) TARGET=armv7a-linux-androideabi; HOST=arm-linux-androideabi ;;
  esac
  BUILD="$WORK/$ABI"
  OUT="$ROOT/core/data/src/main/jniLibs/$ABI"
  mkdir -p "$BUILD" "$OUT"
  (
    cd "$BUILD"
    export CC="$TOOLCHAIN/bin/${TARGET}28-clang"
    export CXX="$TOOLCHAIN/bin/${TARGET}28-clang++"
    export AR="$TOOLCHAIN/bin/llvm-ar" RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    export STRIP="$TOOLCHAIN/bin/llvm-strip"
    export CFLAGS='-O2 -fPIC' CXXFLAGS='-O2 -fPIC'
    export LDFLAGS='-Wl,-z,max-page-size=16384'
    export PKG_CONFIG_LIBDIR=/nonexistent
    "$SOURCE/configure" --host="$HOST" --disable-shared --enable-static \
      --without-libusb --without-readline --without-indi --without-cxx-binding \
      --without-python-binding --without-perl-binding --without-tcl-binding --disable-html-matrix
    make -j2
    "$CC" -c -fPIC -O2 -I"$SOURCE/include" -I"$BUILD/include" \
      "$ROOT/core/data/src/main/cpp/hamlib_jni.c" -o hamlib_jni.o
    "$CXX" -shared -static-libstdc++ -Wl,-z,max-page-size=16384 -Wl,--no-undefined \
      hamlib_jni.o src/.libs/libhamlib.a -lm -llog -landroid -ldl -o "$OUT/liblook4sat_hamlib.so"
    "$STRIP" --strip-unneeded "$OUT/liblook4sat_hamlib.so"
  )
done
# Ship the exact dependency sources and notices with the CI artifacts.
git -C "$SOURCE" archive --format=tar.gz --output="$ROOT/build/hamlib-source.tar.gz" "$SHA"
