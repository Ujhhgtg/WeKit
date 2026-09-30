// Adapted from LSPosed/prefab-libcxx, commit 2145f6305724502bc257044202322735078ba62c,
// cxx/modules/android-module-compat.h (Apache-2.0; see LICENSE).
// The Android guard is omitted: this header is only used by the Android build.
#pragma once

#include <sys/cdefs.h>

// Bionic's C wrappers have internal linkage, which a named module cannot
// export. Preserve their bodies, diagnostics and object-size checks, giving
// only the std module's inline definitions hidden external linkage.
// Do not force-include this header into ordinary consumer translation units.
#define __BIONIC_CTYPE_INLINE __inline __attribute__((__visibility__("hidden")))
#if defined(__BIONIC_FORTIFY)
#undef __BIONIC_FORTIFY_INLINE
#define __BIONIC_FORTIFY_INLINE \
    __inline __attribute__((__no_stack_protector__, __visibility__("hidden"))) __always_inline
#undef __BIONIC_FORTIFY_VARIADIC
#define __BIONIC_FORTIFY_VARIADIC __inline __attribute__((__visibility__("hidden")))
#undef __BIONIC_ERROR_FUNCTION_VISIBILITY
#define __BIONIC_ERROR_FUNCTION_VISIBILITY __unused __attribute__((__visibility__("hidden")))
#endif
