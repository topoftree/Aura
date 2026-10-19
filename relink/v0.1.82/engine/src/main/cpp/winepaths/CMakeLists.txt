# Aura 0.1.82 added integration file; notice dated 2026-10-19. See NOTICE.md and MODIFICATIONS.tsv.
cmake_minimum_required(VERSION 3.22.1)
project(aura_wine_paths C)
add_library(aura_wine_paths SHARED wine_paths.c)
target_link_libraries(aura_wine_paths dl)
target_compile_options(aura_wine_paths PRIVATE -Wall -Wextra -Werror)
target_link_options(aura_wine_paths PRIVATE -Wl,-z,max-page-size=16384)
