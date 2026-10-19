# Aura 0.1.82 added integration file; notice dated 2026-10-19. See NOTICE.md and MODIFICATIONS.tsv.
cmake_minimum_required(VERSION 3.22.1)
project(aura_native_trace C)
add_library(aura_native_trace SHARED native_fd_trace.c)
target_link_libraries(aura_native_trace dl)
target_compile_options(aura_native_trace PRIVATE -Wall -Wextra -Werror -fno-omit-frame-pointer)
target_link_options(aura_native_trace PRIVATE -Wl,-z,max-page-size=16384 -Wl,--no-undefined)
