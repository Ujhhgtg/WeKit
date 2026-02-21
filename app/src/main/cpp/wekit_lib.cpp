#include <jni.h>
#include <string>
#include <android/log.h>
#include "native_crash_handler.h"

#define LOG_TAG "[WeKit-TAG] wekit-native"

//#define ENABLE_WEKIT_LOGS

#if !defined(ENABLE_WEKIT_LOGS)
    #define LOG_SECURE_E(...)
    #define LOG_SECURE(...)
    #define LOG_SECURE_W(...)

#else
#define LOG_SECURE_E(fmt, ...) \
        do { \
            _Pragma("clang diagnostic push") \
            _Pragma("clang diagnostic ignored \"-Wformat-security\"") \
            _Pragma("clang diagnostic ignored \"-Wformat-nonliteral\"") \
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, (char*)skCrypt(fmt), ##__VA_ARGS__); \
            _Pragma("clang diagnostic pop") \
        } while(0)

#define LOG_SECURE(fmt, ...) \
        do { \
            _Pragma("clang diagnostic push") \
            _Pragma("clang diagnostic ignored \"-Wformat-security\"") \
            _Pragma("clang diagnostic ignored \"-Wformat-nonliteral\"") \
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG, (char*)skCrypt(fmt), ##__VA_ARGS__); \
            _Pragma("clang diagnostic pop") \
        } while(0)
        
#define LOG_SECURE_W(fmt, ...) \
        do { \
            _Pragma("clang diagnostic push") \
            _Pragma("clang diagnostic ignored \"-Wformat-security\"") \
            _Pragma("clang diagnostic ignored \"-Wformat-nonliteral\"") \
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, (char*)skCrypt(fmt), ##__VA_ARGS__); \
            _Pragma("clang diagnostic pop") \
        } while(0)

#endif

#define API_EXPORT __attribute__((visibility("default")))
//#define INTERNAL_FUNC __attribute__((visibility("hidden")))

// ==================== Native Crash Handler JNI Functions ====================

/**
 * 安装 Native 崩溃拦截器
 * Java 签名: (Ljava/lang/String;)Z
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_moe_ouom_wekit_utils_crash_NativeCrashHandler_installNative(JNIEnv* env, jobject thiz, jstring crashLogDir) {
    if (crashLogDir == nullptr) {
        return JNI_FALSE;
    }

    const char* dir = env->GetStringUTFChars(crashLogDir, nullptr);
    if (dir == nullptr) {
        return JNI_FALSE;
    }

    jboolean result = install_native_crash_handler(env, dir);
    env->ReleaseStringUTFChars(crashLogDir, dir);

    return result;
}

/**
 * 卸载 Native 崩溃拦截器
 * Java 签名: ()V
 */
extern "C" JNIEXPORT void JNICALL
Java_moe_ouom_wekit_utils_crash_NativeCrashHandler_uninstallNative(JNIEnv* env, jobject thiz) {
    uninstall_native_crash_handler();
}

/**
 * 触发测试崩溃
 * Java 签名: (I)V
 */
extern "C" JNIEXPORT void JNICALL
Java_moe_ouom_wekit_utils_crash_NativeCrashHandler_triggerTestCrashNative(JNIEnv* env, jobject thiz, jint crashType) {
    trigger_test_crash(crashType);
}


API_EXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    return JNI_VERSION_1_6;
}