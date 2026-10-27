cmake_minimum_required(VERSION 3.22.1)

Project(Winlator)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

include_directories(include)

add_library(winlator SHARED
            src/display_cadence.c
            src/drawable.c
            src/host_frame_copy.c
            ../aura_graphics_trace/frame_copy.c
            src/gpu_image.c
            src/ahb_color_probe.c
            src/dri3_hardware_buffer.c
            src/shared_gpu.c
            src/gpu_helper.c
            src/sysvshared_memory.c
            src/xconnector_epoll.c
            src/xinput_stream.c
            src/xoutput_stream.c
            src/arrays.c
            src/ring_buffer.c
            src/wine_registry_editor.c)

# The pool is private to each DSO; guest Mesa and host drawable state cannot bind
# to one another if both libraries are present in the same process.
set_source_files_properties(../aura_graphics_trace/frame_copy.c PROPERTIES COMPILE_OPTIONS "-fvisibility=hidden")

target_link_libraries(winlator
                      log
                      android
                      jnigraphics
                      EGL
                      GLESv2
                      GLESv3)
