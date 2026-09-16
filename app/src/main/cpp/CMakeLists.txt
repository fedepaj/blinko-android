cmake_minimum_required(VERSION 3.22.1)
project(rslog C)
set(RS_CORE ${CMAKE_CURRENT_SOURCE_DIR}/../../../../core)   # git submodule ./core
add_library(rslog SHARED
    rslog_jni.c
    ${RS_CORE}/rs_decoder.c ${RS_CORE}/rs_assembler.c ${RS_CORE}/rs_pack.c ${RS_CORE}/rs_frame.c ${RS_CORE}/rs_rgb.c ${RS_CORE}/rs_rx.c)
target_include_directories(rslog PRIVATE ${RS_CORE})
target_compile_options(rslog PRIVATE -O2 -std=c99)
find_library(log-lib log)
target_link_libraries(rslog ${log-lib})
