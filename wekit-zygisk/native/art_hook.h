// WeKit Zygisk - ART method hook primitive.
#pragma once

#include <jni.h>
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Initialize the ART hook subsystem. It probes the ArtMethod layout, resolves
 * the same suspend/trust ART symbols used by FunBox's hooker, and allocates
 * the executable trampoline pool. Must be called before hook/unhook calls.
 * Returns true on success.
 */
bool art_hook_init(JNIEnv *env);

/**
 * Get the native ArtMethod* pointer for a java.lang.reflect.Executable.
 * Returns 0 on failure.
 */
uintptr_t art_get_art_method(JNIEnv *env, jobject executable);

/**
 * Hook `target` method with a generated trampoline. The trampoline replaces the
 * target ArtMethod argument with `bridge` before jumping to bridge quick code.
 * This preserves ART's quick-call ABI; target must not point directly to the
 * bridge quick entry.
 * Returns true on success.
 */
bool art_hook_method(JNIEnv *env, uintptr_t target_art, uintptr_t backup_art,
                     uintptr_t bridge_art);

/**
 * Unhook: restore target's entry_point from backup.
 * Returns true on success.
 */
bool art_unhook_method(JNIEnv *env, uintptr_t target_art, uintptr_t backup_art);

/** Mark every DexFile backing a BaseDexClassLoader as trusted. */
bool art_trust_class_loader(JNIEnv *env, jobject class_loader);

/** Returns true if art_hook_init has already succeeded. */
bool art_hook_is_initialized(void);

#ifdef __cplusplus
}
#endif
