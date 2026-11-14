# LSPlant's non-standalone build reads the headers and module providers from
# cxx::cxx. Build the NDK's own module with the same flags and libc++ headers as
# its consumers; Cargo links its object code and the matching runtime archives.
include_guard(GLOBAL)

get_filename_component(WEKIT_NDK_TOOLCHAIN "${CMAKE_SYSROOT}" DIRECTORY)
set(WEKIT_STD_MODULE "${WEKIT_NDK_TOOLCHAIN}/share/libc++/v1/std.cppm")
if(NOT EXISTS "${WEKIT_STD_MODULE}")
    message(FATAL_ERROR "The selected NDK does not provide ${WEKIT_STD_MODULE}; use the pinned NDK r30")
endif()

# Apply the same module-only Bionic linkage adjustment as LSPosed's libcxx-full
# package, leaving the installed NDK and ordinary translation units untouched.
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${WEKIT_STD_MODULE}")
file(READ "${WEKIT_STD_MODULE}" WEKIT_STD_SOURCE)
string(REPLACE "#include <__config>"
    "#include \"android-module-compat.h\"\n#include <__config>"
    WEKIT_STD_SOURCE "${WEKIT_STD_SOURCE}")
file(CONFIGURE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/std/std.cppm"
    CONTENT "${WEKIT_STD_SOURCE}" @ONLY)
configure_file("${CMAKE_CURRENT_LIST_DIR}/android-module-compat.h"
    "${CMAKE_CURRENT_BINARY_DIR}/std/android-module-compat.h" COPYONLY)

add_library(wekit_std STATIC)
target_sources(wekit_std PUBLIC FILE_SET CXX_MODULES
    BASE_DIRS "${CMAKE_CURRENT_BINARY_DIR}/std"
    FILES "${CMAKE_CURRENT_BINARY_DIR}/std/std.cppm")
target_include_directories(wekit_std SYSTEM PRIVATE
    "${WEKIT_NDK_TOOLCHAIN}/share/libc++/v1")
target_compile_options(wekit_std PRIVATE -Wno-reserved-module-identifier)
# LSPlant uses -nostdinc++; CMake normally omits this implicit system include
# directory, so pass it explicitly for the module and all its consumers.
target_compile_options(wekit_std PUBLIC
    -nostdinc++ "SHELL:-isystem \"${CMAKE_SYSROOT}/usr/include/c++/v1\"")
set_target_properties(wekit_std PROPERTIES CXX_SCAN_FOR_MODULES ON SYSTEM ON)

add_library(wekit_cxx INTERFACE)
target_include_directories(wekit_cxx SYSTEM INTERFACE "${CMAKE_SYSROOT}/usr/include/c++/v1")
target_link_libraries(wekit_cxx INTERFACE wekit_std)
add_library(cxx::cxx ALIAS wekit_cxx)
