#!/usr/bin/env bash
# SPDX-License-Identifier: MIT

set -Eeuo pipefail

readonly MESA_REPO="${MESA_REPO:-https://gitlab.freedesktop.org/mesa/mesa.git}"
readonly WORK_DIR="${WORK_DIR:-/work}"
readonly OUT_DIR="${OUT_DIR:-/out}"
readonly API_LEVEL="${API_LEVEL:-34}"
readonly ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-/opt/android-ndk-r29}"
readonly PACKAGE_NAME="${PACKAGE_NAME:-Mesa-Turnip-AdrenoTools}"
readonly MAGISK_PACKAGE_NAME="Mesa-Turnip-Magisk"
readonly MAGISK_VULKAN_FILENAME="${MAGISK_VULKAN_FILENAME:-vulkan.adreno.so}"

die() {
  echo "ERROR: $*" >&2
  exit 1
}

trap 'echo "ERROR: build failed at line $LINENO" >&2' ERR

validate_configuration() {
  local command_name

  for command_name in git meson zip readelf patchelf python3; do
    command -v "$command_name" >/dev/null || die "$command_name is required"
  done

  [[ "$API_LEVEL" =~ ^[0-9]+$ ]] || die "API_LEVEL must be a positive integer"
  [[ "$PACKAGE_NAME" =~ ^[A-Za-z0-9]+([A-Za-z0-9-]*[A-Za-z0-9])?$ ]] || \
    die "PACKAGE_NAME may contain only letters, numbers, and internal hyphens"
  [[ "$MAGISK_VULKAN_FILENAME" =~ ^vulkan\.[A-Za-z0-9_-]+\.so$ ]] || \
    die "MAGISK_VULKAN_FILENAME must look like vulkan.adreno.so"

  if [[ -n "${MESA_TAG:-}" ]]; then
    [[ "$MESA_TAG" =~ ^mesa-[0-9]+\.[0-9]+\.[0-9]+$ ]] || \
      die "MESA_TAG must be a final release tag such as mesa-26.2.0"
  else
    echo "=== Discovering latest stable Mesa release ==="
    MESA_TAG="$(
      git ls-remote --tags --refs "$MESA_REPO" \
        | awk -F/ '$NF ~ /^mesa-[0-9]+\.[0-9]+\.[0-9]+$/ { print $NF }' \
        | sort -V \
        | tail -n 1
    )"
    [[ -n "$MESA_TAG" ]] || die "could not discover a stable Mesa release tag"
  fi

  MESA_VERSION="${MESA_TAG#mesa-}"
  MESA_VERSION_CODE="$(awk -F. '{ printf "%d%02d%02d", $1, $2, $3 }' <<< "$MESA_VERSION")"
  BUILD_DIR="$WORK_DIR/build-android-aarch64"
  INSTALL_DIR="$WORK_DIR/install"
  PACKAGE_DIR="$WORK_DIR/package"
  ZIP_PATH="$OUT_DIR/${PACKAGE_NAME}.${MESA_VERSION}.zip"
  MAGISK_PACKAGE_DIR="$WORK_DIR/package-turnip-magisk"
  MAGISK_ZIP_PATH="$OUT_DIR/${MAGISK_PACKAGE_NAME}.${MESA_VERSION}.zip"

  echo "=== Mesa release: $MESA_TAG ==="
  echo "=== Packages: $PACKAGE_NAME and $MAGISK_PACKAGE_NAME ==="
}

prepare_build_directories() {
  MESA_SRC="$WORK_DIR/mesa"
  mkdir -p "$WORK_DIR" "$OUT_DIR"
  rm -rf "$MESA_SRC" "$BUILD_DIR" "$INSTALL_DIR" "$PACKAGE_DIR" \
    "$MAGISK_PACKAGE_DIR" "$ZIP_PATH" "$MAGISK_ZIP_PATH"
}

resolve_mesa_source() {
  git clone --depth=1 --branch "$MESA_TAG" "$MESA_REPO" "$MESA_SRC"
  MESA_COMMIT="$(git -C "$MESA_SRC" rev-parse HEAD)"
  echo "=== Mesa commit: $MESA_COMMIT ==="
}

configure_mesa_build() {
  local toolchain android_clang android_clangxx android_ar android_strip
  toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
  android_clang="$toolchain/bin/aarch64-linux-android${API_LEVEL}-clang"
  android_clangxx="$toolchain/bin/aarch64-linux-android${API_LEVEL}-clang++"
  android_ar="$toolchain/bin/llvm-ar"
  android_strip="$toolchain/bin/llvm-strip"

  [[ -x "$android_clang" ]] || die "missing Android C compiler: $android_clang"
  [[ -x "$android_clangxx" ]] || die "missing Android C++ compiler: $android_clangxx"
  [[ -x "$android_ar" ]] || die "missing Android archiver: $android_ar"
  [[ -x "$android_strip" ]] || die "missing Android strip tool: $android_strip"

  cat > "$WORK_DIR/android-aarch64.txt" <<EOF
[constants]
ndk_path = '$ANDROID_NDK_HOME'
toolchain_path = ndk_path / 'toolchains/llvm/prebuilt/linux-x86_64'

[binaries]
ar = toolchain_path / 'bin/llvm-ar'
c = [toolchain_path / 'bin/aarch64-linux-android${API_LEVEL}-clang']
cpp = [toolchain_path / 'bin/aarch64-linux-android${API_LEVEL}-clang++', '-fno-exceptions', '-fno-unwind-tables', '-fno-asynchronous-unwind-tables', '--start-no-unused-arguments', '-static-libstdc++', '--end-no-unused-arguments']
c_ld = 'lld'
cpp_ld = 'lld'
strip = toolchain_path / 'bin/llvm-strip'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8'
endian = 'little'
EOF

  echo "=== Configuring upstream Android KGSL Turnip ==="
  meson setup "$BUILD_DIR" "$MESA_SRC" \
    --cross-file "$WORK_DIR/android-aarch64.txt" \
    --prefix "$INSTALL_DIR" \
    -Dbuildtype=release \
    -Dstrip=true \
    -Dplatforms=android \
    -Dplatform-sdk-version="$API_LEVEL" \
    -Dandroid-stub=true \
    -Dandroid-libbacktrace=disabled \
    -Degl=disabled \
    -Dgallium-drivers= \
    -Dvulkan-drivers=freedreno \
    -Dvulkan-beta=true \
    -Dfreedreno-kmds=kgsl \
    -Dallow-fallback-for=libdrm \
    -Dvideo-codecs=
}

compile_and_install_mesa() {
  meson compile -C "$BUILD_DIR"
  meson install -C "$BUILD_DIR"

  VULKAN_SO="$INSTALL_DIR/lib/libvulkan_freedreno.so"
  [[ -f "$VULKAN_SO" ]] || die "Mesa did not install $VULKAN_SO"
  readelf -h "$VULKAN_SO" | grep -q 'AArch64' || \
    die "driver is not an AArch64 ELF: $VULKAN_SO"
  readelf -d "$VULKAN_SO" | grep -Eq 'SONAME.*libvulkan_freedreno\.so' || \
    die "driver SONAME is not libvulkan_freedreno.so: $VULKAN_SO"
}

read_vulkan_api_version() {
  python3 - "$MESA_SRC" <<'PY'
from pathlib import Path
import re
import sys

mesa_src = Path(sys.argv[1])
header = (mesa_src / "include/vulkan/vulkan_core.h").read_text()
turnip = (mesa_src / "src/freedreno/vulkan/tu_device.cc").read_text()
header_match = re.search(r"^#define VK_HEADER_VERSION\s+(\d+)\s*$", header, re.MULTILINE)
api_match = re.search(
    r"^#define TU_API_VERSION\s+VK_MAKE_VERSION\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(VK_HEADER_VERSION|\d+)\s*\)",
    turnip,
    re.MULTILINE,
)
if not header_match or not api_match:
    raise SystemExit("could not determine Turnip Vulkan API version from Mesa source")

patch = int(header_match.group(1)) if api_match.group(3) == "VK_HEADER_VERSION" else int(api_match.group(3))
print(f"{api_match.group(1)}.{api_match.group(2)}.{patch}")
PY
}

write_adrenotools_metadata() {
  local vulkan_api_version="$1"

  python3 - "$PACKAGE_DIR/meta.json" "$MESA_VERSION" "$MESA_COMMIT" \
    "$vulkan_api_version" "$API_LEVEL" <<'PY'
import json
import sys

path, mesa_version, mesa_commit, vulkan_api_version, min_api = sys.argv[1:]
metadata = {
    "schemaVersion": 1,
    "name": f"Mesa Turnip {mesa_version}",
    "description": f"Upstream Mesa KGSL build, commit {mesa_commit[:7]}.",
    "author": "Mesa Project",
    "packageVersion": "1",
    "vendor": "Mesa",
    "driverVersion": f"Vulkan {vulkan_api_version}",
    "minApi": int(min_api),
    "libraryName": "libvulkan_freedreno.so",
}
with open(path, "w", encoding="utf-8") as metadata_file:
    json.dump(metadata, metadata_file, indent=2)
    metadata_file.write("\n")
PY

  python3 -m json.tool "$PACKAGE_DIR/meta.json" >/dev/null || \
    die "generated meta.json is invalid"
}

package_adrenotools_driver() {
  local vulkan_api_version="$1"
  mkdir -p "$PACKAGE_DIR"
  cp -L "$VULKAN_SO" "$PACKAGE_DIR/libvulkan_freedreno.so"
  write_adrenotools_metadata "$vulkan_api_version"

  touch -d '1970-01-01 00:00:00 UTC' \
    "$PACKAGE_DIR/libvulkan_freedreno.so" "$PACKAGE_DIR/meta.json"
  rm -f "$ZIP_PATH"
  (cd "$PACKAGE_DIR" && zip -X -9 "$ZIP_PATH" libvulkan_freedreno.so meta.json)

  [[ "$(unzip -Z1 "$ZIP_PATH" | sort)" == $'libvulkan_freedreno.so\nmeta.json' ]] || \
    die "unexpected AdrenoTools ZIP contents"
}

package_magisk_module() {
  local magisk_library
  magisk_library="$MAGISK_PACKAGE_DIR/system/vendor/lib64/hw/$MAGISK_VULKAN_FILENAME"
  mkdir -p "${magisk_library%/*}"
  cp -L "$VULKAN_SO" "$magisk_library"
  patchelf --set-soname "$MAGISK_VULKAN_FILENAME" "$magisk_library"
  readelf -h "$magisk_library" | grep -q 'AArch64' || \
    die "Magisk driver is not an AArch64 ELF: $magisk_library"
  readelf -d "$magisk_library" | grep -Eq "SONAME.*${MAGISK_VULKAN_FILENAME//./\\.}" || \
    die "Magisk driver SONAME does not match $MAGISK_VULKAN_FILENAME"

  cat > "$MAGISK_PACKAGE_DIR/module.prop" <<EOF
id=mesa-turnip
name=Mesa Turnip
version=$MESA_VERSION
versionCode=$MESA_VERSION_CODE
author=Mesa Project
description=Experimental upstream Mesa Turnip Vulkan driver; HAL filename: $MAGISK_VULKAN_FILENAME
EOF
  cat > "$MAGISK_PACKAGE_DIR/README.txt" <<EOF
Mesa Turnip Magisk module
Mesa tag: $MESA_TAG
Mesa commit: $MESA_COMMIT
Vulkan HAL filename: $MAGISK_VULKAN_FILENAME

This is an experimental global Vulkan override. Prefer the AdrenoTools
package with per-app driver selection when testing games or Android apps.
Global replacement can affect Android HWUI and system services. Do not enable
this module together with another module that overlays the same Vulkan HAL.

The Vulkan HAL filename is device-specific. Override MAGISK_VULKAN_FILENAME
when building this package. Keep a recovery path and disable this module from
Magisk Safe Mode if Android fails to boot.
EOF

  touch -d '1970-01-01 00:00:00 UTC' \
    "$MAGISK_PACKAGE_DIR/module.prop" "$MAGISK_PACKAGE_DIR/README.txt" "$magisk_library"
  (cd "$MAGISK_PACKAGE_DIR" && zip -X -9 -r "$MAGISK_ZIP_PATH" module.prop README.txt system)
  unzip -Z1 "$MAGISK_ZIP_PATH" | grep -qx 'module.prop' || \
    die "Magisk module.prop is missing"
  unzip -Z1 "$MAGISK_ZIP_PATH" \
    | grep -qx "system/vendor/lib64/hw/$MAGISK_VULKAN_FILENAME" || \
    die "Magisk Vulkan library is missing"
}

write_checksums() {
  (cd "$OUT_DIR" && sha256sum "$(basename "$ZIP_PATH")" "$(basename "$MAGISK_ZIP_PATH")" > SHA256SUMS.txt)
  (cd "$OUT_DIR" && sha512sum "$(basename "$ZIP_PATH")" "$(basename "$MAGISK_ZIP_PATH")" > SHA512SUMS.txt)
  echo "=== Build complete ==="
  cat "$OUT_DIR/SHA256SUMS.txt"
  cat "$OUT_DIR/SHA512SUMS.txt"
}

main() {
  local vulkan_api_version

  validate_configuration
  prepare_build_directories
  resolve_mesa_source
  configure_mesa_build
  compile_and_install_mesa
  vulkan_api_version="$(read_vulkan_api_version)"
  echo "=== Turnip Vulkan API version: $vulkan_api_version ==="
  package_adrenotools_driver "$vulkan_api_version"
  package_magisk_module
  write_checksums
}

main "$@"
