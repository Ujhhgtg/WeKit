#include <android/log.h>
#include <jni.h>
#include <lsplant.hpp>

#include <cstddef>
#include <exception>
#include <string_view>

using ArtSymbolResolver = void *(*)(const char *, std::size_t, bool);

using InlineHook = void *(*)(void *, void *);
using InlineUnhook = bool (*)(void *);

namespace {

void report_exception(JNIEnv *env, const char *operation, const char *message) noexcept {
    __android_log_print(ANDROID_LOG_ERROR, "WeKit", "LSPlant %s: %s", operation, message);
    if (!env->ExceptionCheck()) {
        if (jclass exception = env->FindClass("java/lang/IllegalStateException")) {
            env->ThrowNew(exception, message);
            env->DeleteLocalRef(exception);
        }
    }
}

template <typename Result, typename Function>
Result guarded(JNIEnv *env, const char *operation, Result failure, Function function) noexcept {
    try {
        return function();
    } catch (const std::exception &error) {
        report_exception(env, operation, error.what());
    } catch (...) {
        report_exception(env, operation, "unknown C++ exception");
    }
    return failure;
}

} // namespace

// Rust owns initialization serialization and the ART symbol resolver's lifetime.
extern "C" bool wekit_lsplant_init(JNIEnv *env, ArtSymbolResolver resolver,
                                    InlineHook hook, InlineUnhook unhook) noexcept {
    return guarded(env, "Init", false, [&] {
        lsplant::InitInfo info{
            .inline_hooker = hook,
            .inline_unhooker = unhook,
            .art_symbol_resolver = [resolver](std::string_view name) {
                return resolver(name.data(), name.size(), false);
            },
            .art_symbol_prefix_resolver = [resolver](std::string_view name) {
                return resolver(name.data(), name.size(), true);
            },
            .executable_memory_allocator = {},
            .executable_memory_recycler = {},
        };
        return lsplant::Init(env, info);
    });
}

extern "C" jobject wekit_lsplant_hook(JNIEnv *env, jobject target, jobject hooker,
                                      jobject callback) noexcept {
    return guarded(env, "Hook", static_cast<jobject>(nullptr), [&]() -> jobject {
        jobject backup = lsplant::Hook(env, target, hooker, callback);
        // LSPlant retains and owns its global reference. JNI callers receive a
        // separate local reference and must never delete the upstream global.
        return backup ? env->NewLocalRef(backup) : nullptr;
    });
}

extern "C" bool wekit_lsplant_is_hooked(JNIEnv *env, jobject target) noexcept {
    return guarded(env, "IsHooked", false, [&] { return lsplant::IsHooked(env, target); });
}

extern "C" bool wekit_lsplant_deoptimize(JNIEnv *env, jobject target) noexcept {
    return guarded(env, "Deoptimize", false, [&] { return lsplant::Deoptimize(env, target); });
}

extern "C" bool wekit_lsplant_make_dex_file_trusted(JNIEnv *env, jobject cookie) noexcept {
    return guarded(env, "MakeDexFileTrusted", false,
                   [&] { return lsplant::MakeDexFileTrusted(env, cookie); });
}
