cmake_minimum_required(VERSION 3.22.1)
project(blinko C)
set(RS_CORE ${CMAKE_CURRENT_SOURCE_DIR}/../../../../core)   # git submodule ./core
add_library(blinko SHARED
    blinko_jni.c
    ${RS_CORE}/rs_decoder.c ${RS_CORE}/rs_assembler.c ${RS_CORE}/rs_pack.c ${RS_CORE}/rs_frame.c ${RS_CORE}/rs_rgb.c ${RS_CORE}/rs_rx.c ${RS_CORE}/rs_multi.c ${RS_CORE}/rs_stitch.c)
target_include_directories(blinko PRIVATE ${RS_CORE})
target_compile_options(blinko PRIVATE -O2 -std=c99 -DRS_DEC_THREADS)   # thread-local decoder scratch: the three channels of a frame decode on three threads
find_library(log-lib log)
target_link_libraries(blinko ${log-lib})
