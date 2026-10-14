#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CPP_DIR="$ROOT/app/src/main/cpp"
NDK="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME не задан}"
API="${ANDROID_API:-24}"
REF="${DOBBY_REF:-master}"
WORK="$(mktemp -d)"
ABIS=("arm64-v8a" "armeabi-v7a")

echo ">>> Скачиваем исходники Dobby ($REF)"
curl -fsSL "https://github.com{REF}.tar.gz" -o "$WORK/dobby.tar.gz"
tar -xzf "$WORK/dobby.tar.gz" -C "$WORK"
SRC="$(echo "$WORK"/Dobby-*)"

echo ">>> Копируем заголовок dobby.h"
mkdir -p "$CPP_DIR/dobby/include"
cp "$SRC/include/dobby.h" "$CPP_DIR/dobby/include/"

for ABI in "${ABIS[@]}"; do
  echo ">>> Собираем Dobby для $ABI"
  BUILD="$WORK/build-$ABI"

  cmake -S "$SRC" -B "$BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API" \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DDOBBY_GENERATE_SHARED=ON

  cmake --build "$BUILD" -j"$(nproc)"

  SO="$(find "$BUILD" -name libdobby.so | head -n 1)"
  if [ -z "$SO" ]; then
    echo "ОШИБКА: libdobby.so не собрался для $ABI."
    exit 1
  fi

  # Копируем для сборщика CMake
  mkdir -p "$CPP_DIR/libs/$ABI"
  cp "$SO" "$CPP_DIR/libs/$ABI/libdobby.so"

  # Копируем для упаковки в APK
  mkdir -p "$ROOT/app/src/main/jniLibs/$ABI"
  cp "$SO" "$ROOT/app/src/main/jniLibs/$ABI/libdobby.so"
done

rm -rf "$WORK"
echo ">>> Сборка Dobby завершена."
