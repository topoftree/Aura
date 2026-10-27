cmake_minimum_required(VERSION 3.22.1)
project(aura_network_probe C)
add_library(aura_network_probe SHARED network_probe.c raw_dns.c)
target_link_libraries(aura_network_probe android dl)
target_compile_options(aura_network_probe PRIVATE -Wall -Wextra -Werror)
target_link_options(aura_network_probe PRIVATE -Wl,-z,max-page-size=16384 -Wl,--no-undefined)

# The app loads only this JNI library, never the getaddrinfo interposer above.
add_library(aura_network_host SHARED host_resolver.c)
target_link_libraries(aura_network_host android)
target_compile_options(aura_network_host PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_options(aura_network_host PRIVATE -Wl,-z,max-page-size=16384 -Wl,--no-undefined)
