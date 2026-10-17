#!/usr/bin/env bash
# Собирает статическую libdobby.a для Android и кладёт в app/src/main/cpp/dobby/
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/cpp/dobby"
NDK="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME не задан}"
API="${ANDROID_API:-24}"
REF="${DOBBY_REF:-master}"
ABIS=(${DOBBY_ABIS:-arm64-v8a})
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo ">>> Клонирую Dobby ($REF)"
git clone --depth 1 --branch "$REF" https://github.com/jmpews/Dobby.git "$WORK/Dobby"
SRC="$WORK/Dobby"

echo ">>> Патчу Dobby (циклический include на Android)"
python3 "$ROOT/scripts/patch_dobby.py" "$SRC"

mkdir -p "$OUT/include"
cp "$SRC/include/dobby.h" "$OUT/include/"

for ABI in "${ABIS[@]}"; do
  echo ">>> Собираю Dobby для $ABI"
  BUILD="$WORK/build-$ABI"
  cmake -S "$SRC" -B "$BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API" \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DDOBBY_GENERATE_SHARED=OFF \
    -DDOBBY_DEBUG=OFF \
    -DPlugin.SymbolResolver=OFF
  cmake --build "$BUILD" -j"$(nproc)"

  LIB="$(find "$BUILD" -name 'libdobby.a' | head -n 1)"
  if [ -z "$LIB" ]; then
    echo "ОШИБКА: libdobby.a не собралась для $ABI"
    exit 1
  fi
  mkdir -p "$OUT/lib/$ABI"
  cp "$LIB" "$OUT/lib/$ABI/libdobby.a"
done

echo ">>> Готово: $OUT"
