// WeKit Zygisk — SoHider: remap on-disk SO/APK mappings to anonymous memfd
// Uses memfd remapping when the running kernel supports it.
#pragma once

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Hide all /proc/self/maps entries whose path contains `needle`.
 *
 * Each matching file-backed mapping is:
 *   1. Copied into a fresh anonymous memfd.
 *   2. Re-mapped at the original virtual address with the original protection.
 *
 * After this call the mappings no longer show a file path in maps/smaps, so
 * WeChat's native module-detection scan sees no on-disk path for our library.
 *
 * Also clears matching public link_map names used by dl_iterate_phdr, using the
 * ELF DT_DEBUG/r_debug ABI rather than private linker structure offsets.
 * SYS_memfd_create is detected at runtime, including on Android 9/10.
 *
 * Returns the number of segments successfully remapped, or -1 on fatal error.
 */
int so_hide_path(const char *needle);

#ifdef __cplusplus
}
#endif
