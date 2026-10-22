cmake_minimum_required(VERSION 3.22.1)
project(aura_alsa_plugin C)
file(READ "${CMAKE_CURRENT_LIST_DIR}/../../assets/aura-native-index.json" native_index)
file(READ "${CMAKE_CURRENT_LIST_DIR}/../../assets/arm64ec/manifest.json" runtime_manifest)
string(JSON asound_hash GET "${runtime_manifest}" files "usr/lib/libasound.so" sha256)
string(JSON asound_library GET "${native_index}" files "${asound_hash}")
add_library(aura_alsa_plugin SHARED pcm_android_aserver.c)
target_include_directories(aura_alsa_plugin PRIVATE "${CMAKE_CURRENT_LIST_DIR}")
target_compile_definitions(aura_alsa_plugin PRIVATE PIC)
target_compile_options(aura_alsa_plugin PRIVATE -Wall -Wextra -Werror)
# Link by SONAME without adding another AGP imported-library packaging input.
# libasound already has a single APK copy in the runtime native index.
target_link_options(aura_alsa_plugin PRIVATE "-L${CMAKE_CURRENT_LIST_DIR}/../../jniLibs/arm64-v8a"
    -Wl,--no-as-needed "-Wl,-l:${asound_library}")
target_link_options(aura_alsa_plugin PRIVATE -Wl,-z,max-page-size=16384)
