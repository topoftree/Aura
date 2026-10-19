# Aura 0.1.82 added integration file; notice dated 2026-10-19. See NOTICE.md and MODIFICATIONS.tsv.
cmake_minimum_required(VERSION 3.22.1)
project(aura_game_probe C)
add_library(aura_game_probe SHARED game_probe.c game_probe_jni.c fex_guest_pc.c ec_entry_return.c wine_alloc_probe.c address_space_probe.c)
target_compile_options(aura_game_probe PRIVATE -Wall -Wextra -Werror -fno-omit-frame-pointer)
target_link_options(aura_game_probe PRIVATE -Wl,-z,max-page-size=16384 -Wl,--no-undefined)
