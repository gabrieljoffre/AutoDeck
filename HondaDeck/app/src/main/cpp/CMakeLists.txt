cmake_minimum_required(VERSION 3.22.1)
project(stk1160_native C)

add_library(stk1160_native SHARED
    stk1160_jni.c
)

target_compile_options(stk1160_native PRIVATE
    -O2
    -Wall
    -Wextra
)

find_library(android-lib android)
find_library(log-lib log)

target_link_libraries(stk1160_native
    ${android-lib}
    ${log-lib}
)
