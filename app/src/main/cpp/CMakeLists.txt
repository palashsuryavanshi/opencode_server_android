# Spec 33 - the native PTY bridge.
#
# One library, built for every ABI the app targets, because spec 2 requires a runtime for each
# architecture rather than a single bundled binary. AGP drives this from the `abiFilters` list in
# app/build.gradle.kts.

cmake_minimum_required(VERSION 3.22.1)

project("opencodepty")

add_library(
        opencodepty
        SHARED
        pty_bridge.cpp)

target_compile_options(
        opencodepty
        PRIVATE
        -Wall
        -Wextra
        -Werror
        -fvisibility=hidden
        -fexceptions
        -frtti)

# The API level is supplied by AGP as __ANDROID_API__, derived from the app's minSdk. Defining it
# again here would be a macro redefinition, so it is deliberately not set.

find_library(log-lib log)
find_library(android-lib android)

# __android_log_print lives in liblog; the JNI and forkpty pieces come from the platform libraries.
# Each is linked only when found, because a NOTFOUND result would otherwise be passed through as a
# literal "-l<NOTFOUND>" and fail at link time with a confusing message.
if(log-lib)
    target_link_libraries(opencodepty ${log-lib})
endif()

if(android-lib)
    target_link_libraries(opencodepty ${android-lib})
endif()