#!/usr/bin/env bash
# Собирает мод Bearite в готовый zip: компилирует src/*.c и src/*.cpp в .so и пакует всё рядом с mod.json.
#
#   bash build-mod.sh <папка-мода> [папка-результата]      (результат по умолчанию: dist/)
#
# Нужны: Android NDK (переменная ANDROID_NDK_HOME) и python3.
# В mod.json поле "libs" должно указывать, куда положить библиотеку, например
#   "libs": ["libs/arm64-v8a/libmymod.so"]
# Папку src/ в zip не кладём, остальное содержимое папки мода (icon.png, бандлы) кладём как есть.
#
# Для отладки можно подменить компилятор: CC=gcc CXX=g++ LDLIBS="-ldl -lm" bash build-mod.sh ...
set -euo pipefail

MOD_DIR="${1:?Укажи папку мода: bash build-mod.sh mods/my-mod}"
OUT="${2:-dist}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOD_DIR="$(cd "$MOD_DIR" && pwd)"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

[ -f "$MOD_DIR/mod.json" ] || { echo "Нет mod.json в $MOD_DIR" >&2; exit 1; }

# id мода и путь к библиотеке из mod.json
INFO="$(python3 - "$MOD_DIR/mod.json" <<'PY'
import json, sys
m = json.load(open(sys.argv[1], encoding="utf-8"))
libs = m.get("libs") or []
print(m.get("id", ""))
print(libs[0] if libs else "")
PY
)"
ID="$(printf '%s\n' "$INFO" | sed -n 1p)"
LIB="$(printf '%s\n' "$INFO" | sed -n 2p)"
[ -n "$ID" ]  || { echo "В mod.json нет id" >&2; exit 1; }
[ -n "$LIB" ] || { echo "В mod.json нет libs, например \"libs\": [\"libs/arm64-v8a/lib$ID.so\"]" >&2; exit 1; }

# компилятор: из NDK, если не задан вручную
if [ -z "${CC:-}" ]; then
  NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_NDK_LATEST_HOME:-}}}"
  [ -n "$NDK" ] || { echo "Нужен Android NDK: задай ANDROID_NDK_HOME" >&2; exit 1; }
  TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
  API="${ANDROID_API:-24}"
  CC="$TC/aarch64-linux-android${API}-clang"
  CXX="$TC/aarch64-linux-android${API}-clang++"
  NM="${NM:-$TC/llvm-nm}"
fi
CXX="${CXX:-$CC}"
NM="${NM:-nm}"
CFLAGS_ALL="-fPIC -O2 -Wall -fvisibility=hidden ${EXTRA_CFLAGS:-}"
LDLIBS="${LDLIBS:--llog -ldl -lm}"

shopt -s nullglob
C_SRC=("$MOD_DIR"/src/*.c)
CPP_SRC=("$MOD_DIR"/src/*.cpp)
[ $(( ${#C_SRC[@]} + ${#CPP_SRC[@]} )) -gt 0 ] || { echo "В $MOD_DIR/src нет .c или .cpp файлов" >&2; exit 1; }

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
PKG="$STAGE/pkg"
mkdir -p "$PKG" "$STAGE/obj"
cp -r "$MOD_DIR"/. "$PKG"/
rm -rf "$PKG/src"
mkdir -p "$PKG/$(dirname "$LIB")"

OBJ=()
i=0
for f in "${C_SRC[@]}"; do
  o="$STAGE/obj/$((i++)).o"
  # shellcheck disable=SC2086
  "$CC" -c $CFLAGS_ALL -I"$HERE" -I"$MOD_DIR/src" "$f" -o "$o"
  OBJ+=("$o")
done
for f in "${CPP_SRC[@]}"; do
  o="$STAGE/obj/$((i++)).o"
  # shellcheck disable=SC2086
  "$CXX" -c -std=c++17 $CFLAGS_ALL -I"$HERE" -I"$MOD_DIR/src" "$f" -o "$o"
  OBJ+=("$o")
done

LINKER="$CC"
EXTRA_LINK=""
if [ ${#CPP_SRC[@]} -gt 0 ]; then LINKER="$CXX"; EXTRA_LINK="-static-libstdc++"; fi
# shellcheck disable=SC2086
"$LINKER" -shared -Wl,-z,max-page-size=16384 $EXTRA_LINK -o "$PKG/$LIB" "${OBJ[@]}" $LDLIBS

# мод должен экспортировать bearite_mod_init
if ! "$NM" -D --defined-only "$PKG/$LIB" | grep -qw bearite_mod_init; then
  echo "В библиотеке нет экспортируемой функции bearite_mod_init (проверь visibility(\"default\"))" >&2
  exit 1
fi

python3 - "$PKG" "$OUT/$ID.zip" <<'PY'
import os, sys, zipfile
src, dst = sys.argv[1:3]
with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(src):
        for name in sorted(files):
            p = os.path.join(root, name)
            z.write(p, os.path.relpath(p, src))
PY
echo "Готово: $OUT/$ID.zip"
