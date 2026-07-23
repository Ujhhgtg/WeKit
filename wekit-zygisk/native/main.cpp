// WeKit Zygisk — main module entry point
#include <sys/types.h>

#include "zygisk.hpp"
#include "art_hook.h"
#include "so_hider.h"

#include <android/log.h>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <fstream>
#include <jni.h>
#include <limits>
#include <sstream>
#include <string>
#include <sys/sendfile.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

using zygisk::Api;
using zygisk::AppSpecializeArgs;
using zygisk::ServerSpecializeArgs;

#define TAG "WekitZygisk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ─── Companion protocol ───────────────────────────────────────────────────────
// Module → companion: request byte 0x02, uid, UTF-8 process-name length and bytes.
// Companion → module: int64_t APK size (zero = target is disabled/mismatched,
// negative = error), then fd via SCM_RIGHTS for a positive size.

static constexpr uint8_t COMPANION_REQUEST_APK = 0x02;
static constexpr uint16_t MAX_PROCESS_NAME_BYTES = 255;
static constexpr int APP_USER_RANGE = 100000;
static constexpr const char* TARGETS_PATH = "/data/adb/wekit/injection-targets.tsv";
static constexpr const char* WECHAT_PACKAGE = "com.tencent.mm";

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif
#ifndef MFD_ALLOW_SEALING
#define MFD_ALLOW_SEALING 0x0002U
#endif
#ifndef F_ADD_SEALS
#define F_ADD_SEALS 1033
#define F_SEAL_SEAL 0x0001
#define F_SEAL_SHRINK 0x0002
#define F_SEAL_GROW 0x0004
#define F_SEAL_WRITE 0x0008
#endif

static const char* current_abi_dir();

static bool send_fd(int sock, int fd) {
    char dummy = 0;
    struct iovec iov = { &dummy, 1 };
    char ctrl_buf[CMSG_SPACE(sizeof(int))] = {};
    struct msghdr msg = {};
    msg.msg_iov        = &iov;
    msg.msg_iovlen     = 1;
    msg.msg_control    = ctrl_buf;
    msg.msg_controllen = sizeof(ctrl_buf);
    struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type  = SCM_RIGHTS;
    cmsg->cmsg_len   = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &fd, sizeof(int));
    return sendmsg(sock, &msg, 0) >= 0;
}

static int recv_fd(int sock) {
    char dummy = 0;
    struct iovec iov = { &dummy, 1 };
    char ctrl_buf[CMSG_SPACE(sizeof(int))] = {};
    struct msghdr msg = {};
    msg.msg_iov        = &iov;
    msg.msg_iovlen     = 1;
    msg.msg_control    = ctrl_buf;
    msg.msg_controllen = sizeof(ctrl_buf);
    ssize_t rc = recvmsg(sock, &msg, 0);
    if (rc < 0) return -1;
    struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
    if (!cmsg || cmsg->cmsg_type != SCM_RIGHTS) return -1;
    int fd = -1;
    memcpy(&fd, CMSG_DATA(cmsg), sizeof(int));
    return fd;
}

static bool write_all(int fd, const void* buf, size_t len) {
    const char* p = static_cast<const char*>(buf);
    size_t written = 0;
    while (written < len) {
        ssize_t r = write(fd, p + written, len - written);
        if (r <= 0) return false;
        written += static_cast<size_t>(r);
    }
    return true;
}

static bool read_all(int fd, void* buf, size_t len) {
    char* p = static_cast<char*>(buf);
    size_t got = 0;
    while (got < len) {
        ssize_t r = read(fd, p + got, len - got);
        if (r <= 0) return false;
        got += static_cast<size_t>(r);
    }
    return true;
}

static bool is_process_for_package(const std::string& process_name,
                                   const std::string& package_name) {
    if (package_name.empty() || process_name.size() < package_name.size()) return false;
    if (process_name.compare(0, package_name.size(), package_name) != 0) return false;
    return process_name.size() == package_name.size() ||
           process_name[package_name.size()] == ':';
}

static bool parse_nonnegative_int(const std::string& value, int& out) {
    if (value.empty()) return false;
    char* end = nullptr;
    errno = 0;
    const long parsed = strtol(value.c_str(), &end, 10);
    if (errno != 0 || end == value.c_str() || *end != '\0' || parsed < 0 ||
        parsed > std::numeric_limits<int>::max()) {
        return false;
    }
    out = static_cast<int>(parsed);
    return true;
}

// The WebUI stores one tab-separated row per target: userId, packageName,
// enabled. This parser deliberately ignores malformed rows: an unreadable or
// malformed allow-list must fail closed rather than inject an unexpected app.
static bool is_enabled_target(jint uid, const std::string& process_name) {
    if (uid < 0 || process_name.empty()) return false;
    const int user_id = uid / APP_USER_RANGE;

    std::ifstream config(TARGETS_PATH);
    if (!config.is_open()) return false;

    std::string line;
    while (std::getline(config, line)) {
        if (line.empty() || line[0] == '#') continue;

        std::istringstream row(line);
        std::string user_text;
        std::string package_name;
        std::string enabled;
        if (!std::getline(row, user_text, '\t') ||
            !std::getline(row, package_name, '\t') ||
            !std::getline(row, enabled, '\t')) {
            continue;
        }

        int target_user = -1;
        if (!parse_nonnegative_int(user_text, target_user) || target_user != user_id ||
            package_name != WECHAT_PACKAGE || enabled != "1") {
            continue;
        }
        if (is_process_for_package(process_name, package_name)) return true;
    }
    return false;
}

// Companion handler — runs in root context.
static void companion_handler(int sock) {
    uint8_t request = 0;
    jint uid = -1;
    uint16_t process_len = 0;
    if (!read_all(sock, &request, sizeof(request)) || request != COMPANION_REQUEST_APK ||
        !read_all(sock, &uid, sizeof(uid)) ||
        !read_all(sock, &process_len, sizeof(process_len)) || process_len == 0 ||
        process_len > MAX_PROCESS_NAME_BYTES) {
        LOGE("companion: invalid request");
        return;
    }

    std::string process_name(process_len, '\0');
    if (!read_all(sock, &process_name[0], process_name.size())) {
        LOGE("companion: failed to read process name");
        return;
    }

    if (!is_enabled_target(uid, process_name)) {
        // Disabled is a normal result. It is intentionally silent because this
        // handler is reached for every app process Zygisk specializes.
        const int64_t disabled = 0;
        write_all(sock, &disabled, sizeof(disabled));
        return;
    }

    const char* abi = current_abi_dir();
    if (!abi) {
        LOGE("companion: unsupported ABI");
        int64_t err = -1;
        write_all(sock, &err, sizeof(err));
        return;
    }
    const std::string apk_path =
        std::string("/data/adb/modules/wekit/payload/wekit-") + abi + ".apk";
    int apk_fd = open(apk_path.c_str(), O_RDONLY | O_CLOEXEC);
    if (apk_fd < 0) {
        LOGE("companion: cannot open %s: %s", apk_path.c_str(), strerror(errno));
        // BUG-6 fix: send int64_t (same width as the size field the module reads).
        int64_t err = -1;
        write_all(sock, &err, sizeof(err));
        return;
    }

    struct stat st{};
    // BUG-7 fix: check fstat return value.
    if (fstat(apk_fd, &st) != 0) {
        LOGE("companion: fstat failed: %s", strerror(errno));
        close(apk_fd);
        int64_t err = -1;
        write_all(sock, &err, sizeof(err));
        return;
    }
    int64_t size = static_cast<int64_t>(st.st_size);

    // Send size first, then fd via SCM_RIGHTS.
    if (!write_all(sock, &size, sizeof(size))) {
        LOGE("companion: failed to send APK size");
        close(apk_fd);
        return;
    }
    if (!send_fd(sock, apk_fd)) {
        LOGE("companion: failed to send APK fd");
    }
    close(apk_fd);
    LOGI("companion: sent APK fd (size=%lld)", (long long)size);
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

static int memfd_create_compat(const char* name, unsigned int flags) {
    return static_cast<int>(syscall(SYS_memfd_create, name, flags));
}

static std::string fd_path(int fd) {
    return "/proc/self/fd/" + std::to_string(fd);
}

// Build a process-private APK after specialization. Isolated UIDs cannot access
// the package data directory, but they can keep and reopen their own memfd.
static int copy_apk_to_memfd(int source_fd, int64_t apk_size) {
    if (source_fd < 0 || apk_size <= 0 ||
        static_cast<uint64_t>(apk_size) >
            static_cast<uint64_t>(std::numeric_limits<off_t>::max())) {
        return -1;
    }

    int writable_fd = memfd_create_compat("wekit-payload.apk", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (writable_fd < 0) {
        LOGE("payload memfd_create failed: %s", strerror(errno));
        return -1;
    }
    if (fchmod(writable_fd, 0600) != 0 ||
        ftruncate(writable_fd, static_cast<off_t>(apk_size)) != 0 ||
        lseek(source_fd, 0, SEEK_SET) < 0) {
        LOGE("payload memfd setup failed: %s", strerror(errno));
        close(writable_fd);
        return -1;
    }

    int64_t remaining = apk_size;
    while (remaining > 0) {
        const size_t chunk = static_cast<size_t>(remaining < 65536 ? remaining : 65536);
        const ssize_t copied = sendfile(writable_fd, source_fd, nullptr, chunk);
        if (copied <= 0) {
            LOGE("payload memfd copy failed: %s", strerror(errno));
            close(writable_fd);
            return -1;
        }
        remaining -= copied;
    }

    const int seals = F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL;
    if (fsync(writable_fd) != 0 || fchmod(writable_fd, 0400) != 0 ||
        fcntl(writable_fd, F_ADD_SEALS, seals) != 0) {
        LOGE("payload memfd finalize failed: %s", strerror(errno));
        close(writable_fd);
        return -1;
    }

    const std::string writable_path = fd_path(writable_fd);
    const int readonly_fd = open(writable_path.c_str(), O_RDONLY | O_CLOEXEC);
    if (readonly_fd < 0) {
        LOGE("payload memfd reopen failed: %s", strerror(errno));
        close(writable_fd);
        return -1;
    }
    close(writable_fd);
    LOGI("payload: prepared sealed memfd=%d size=%lld", readonly_fd,
         static_cast<long long>(apk_size));
    return readonly_fd;
}

static const char* current_abi_dir() {
#if defined(__aarch64__)
    return "arm64-v8a";
#elif defined(__arm__)
    return "armeabi-v7a";
#elif defined(__x86_64__)
    return "x86_64";
#elif defined(__i386__)
    return "x86";
#else
    return nullptr;
#endif
}

// Load the WeKit APK into the JVM via PathClassLoader.
// The host loader used by ModuleLoader is captured later from
// LoadedApk.createAppFactory; this bootstrap loader only needs to resolve module
// and framework classes.
static bool load_dex_from_apk(JNIEnv* env, const std::string& apk_path,
                               jobject& out_classloader) {
    jclass pcl_class = env->FindClass("dalvik/system/PathClassLoader");
    if (!pcl_class) { env->ExceptionClear(); return false; }

    jmethodID pcl_ctor = env->GetMethodID(pcl_class, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
    if (!pcl_ctor) {
        env->ExceptionClear();
        env->DeleteLocalRef(pcl_class);
        return false;
    }

    // Preserve a context parent when one is already available. The real host
    // loader is still obtained later from LoadedApk.createAppFactory.
    jobject parent_cl = nullptr;
    {
        jclass thread_class = env->FindClass("java/lang/Thread");
        if (thread_class) {
            jmethodID current_mid = env->GetStaticMethodID(thread_class, "currentThread",
                                                            "()Ljava/lang/Thread;");
            jmethodID get_cl_mid  = env->GetMethodID(thread_class, "getContextClassLoader",
                                                      "()Ljava/lang/ClassLoader;");
            if (current_mid && get_cl_mid) {
                jobject cur_thread = env->CallStaticObjectMethod(thread_class, current_mid);
                if (cur_thread && !env->ExceptionCheck()) {
                    parent_cl = env->CallObjectMethod(cur_thread, get_cl_mid);
                    if (env->ExceptionCheck()) { env->ExceptionClear(); parent_cl = nullptr; }
                    env->DeleteLocalRef(cur_thread);
                } else {
                    env->ExceptionClear();
                }
            } else {
                env->ExceptionClear();
            }
            env->DeleteLocalRef(thread_class);
        } else {
            env->ExceptionClear();
        }
    }

    // Fallback to system ClassLoader if context CL is unavailable.
    if (!parent_cl) {
        LOGW("load_dex: context CL unavailable, falling back to system CL");
        jclass cl_class = env->FindClass("java/lang/ClassLoader");
        if (!cl_class) { env->ExceptionClear(); env->DeleteLocalRef(pcl_class); return false; }
        jmethodID get_sys = env->GetStaticMethodID(cl_class, "getSystemClassLoader",
                                                    "()Ljava/lang/ClassLoader;");
        if (get_sys) {
            parent_cl = env->CallStaticObjectMethod(cl_class, get_sys);
            if (env->ExceptionCheck()) { env->ExceptionClear(); parent_cl = nullptr; }
        } else {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(cl_class);
    }

    const char* abi = current_abi_dir();
    if (!abi) {
        LOGE("load_dex: unsupported ABI");
        if (parent_cl) env->DeleteLocalRef(parent_cl);
        env->DeleteLocalRef(pcl_class);
        return false;
    }
    const bool used_context_parent = parent_cl != nullptr;
    std::string library_path = apk_path + "!/lib/" + abi;
    jstring apk_str = env->NewStringUTF(apk_path.c_str());
    jstring library_str = env->NewStringUTF(library_path.c_str());
    jobject pcl = env->NewObject(pcl_class, pcl_ctor, apk_str, library_str, parent_cl);

    env->DeleteLocalRef(apk_str);
    env->DeleteLocalRef(library_str);
    if (parent_cl) env->DeleteLocalRef(parent_cl);
    env->DeleteLocalRef(pcl_class);

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        if (pcl) env->DeleteLocalRef(pcl);
        return false;
    }
    if (!pcl) return false;

    out_classloader = env->NewGlobalRef(pcl);
    env->DeleteLocalRef(pcl);
    LOGI("load_dex: loaded APK via PathClassLoader (parent=%s, native=%s)",
         used_context_parent ? "context CL" : "system CL", library_path.c_str());
    return true;
}

// Get the on-disk path of this SO via dladdr.
static std::string get_self_so_path() {
    Dl_info di{};
    if (dladdr(reinterpret_cast<void*>(&companion_handler), &di) && di.dli_fname) {
        return di.dli_fname;
    }
    return "/data/adb/modules/wekit/zygisk/" +
#if defined(__aarch64__)
        std::string("arm64-v8a.so");
#elif defined(__arm__)
        std::string("armeabi-v7a.so");
#elif defined(__x86_64__)
        std::string("x86_64.so");
#elif defined(__i386__)
        std::string("x86.so");
#else
        std::string("arm64-v8a.so");
#endif
}

extern "C" {
JNIEXPORT jlong JNICALL
Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ZygiskHookBridge_nativeGetArtMethod(
    JNIEnv* env, jclass clazz, jobject executable);
JNIEXPORT jint JNICALL
Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ZygiskHookBridge_nativeHookMethod(
    JNIEnv* env, jclass clazz, jlong target_art, jlong backup_art, jlong bridge_art, jlong hook_id);
JNIEXPORT jint JNICALL
Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ZygiskHookBridge_nativeUnhookMethod(
    JNIEnv* env, jclass clazz, jlong target_art, jlong backup_art);
JNIEXPORT jboolean JNICALL
Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ZygiskHookBridge_nativeTrustClassLoader(
    JNIEnv* env, jclass clazz, jobject class_loader);
JNIEXPORT jobject JNICALL
Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ZygiskHookBridge_nativeAllocateInstance(
    JNIEnv* env, jclass clazz, jclass target_class);
JNIEXPORT jboolean JNICALL
Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ZygiskHookBridge_nativeHideLoadedModuleLibraries(
    JNIEnv* env, jclass clazz);
}

// ─── Module class ─────────────────────────────────────────────────────────────

class WekitZygisk : public zygisk::ModuleBase {
public:
    void onLoad(Api* _api, JNIEnv* _env) override {
        api = _api;
        env = _env;
    }

    void preAppSpecialize(AppSpecializeArgs* args) override {
        std::string process_name;
        if (args->nice_name) {
            const char* value = env->GetStringUTFChars(args->nice_name, nullptr);
            if (value) {
                process_name = value;
                env->ReleaseStringUTFChars(args->nice_name, value);
            } else {
                env->ExceptionClear();
            }
        }
        if (process_name.empty() || process_name.size() > MAX_PROCESS_NAME_BYTES) {
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        const size_t process_separator = process_name.find(':');
        target_package = process_name.substr(0, process_separator);
        if (target_package.empty()) {
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        if (args->app_data_dir) {
            const char* value = env->GetStringUTFChars(args->app_data_dir, nullptr);
            if (value) {
                data_dir = value;
                env->ReleaseStringUTFChars(args->app_data_dir, value);
            } else {
                env->ExceptionClear();
            }
        }
        int sock = api->connectCompanion();
        if (sock < 0) {
            LOGE("preAppSpecialize: connectCompanion failed");
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        const uint8_t request = COMPANION_REQUEST_APK;
        const uint16_t process_len = static_cast<uint16_t>(process_name.size());
        if (!write_all(sock, &request, sizeof(request)) ||
            !write_all(sock, &args->uid, sizeof(args->uid)) ||
            !write_all(sock, &process_len, sizeof(process_len)) ||
            !write_all(sock, process_name.data(), process_name.size())) {
            LOGE("preAppSpecialize: failed to send companion request");
            close(sock);
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        if (!read_all(sock, &apk_size, sizeof(apk_size))) {
            LOGE("preAppSpecialize: failed to read APK size");
            close(sock);
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        if (apk_size == 0) {
            // The target is disabled or the process does not belong to an
            // enabled package. This is the expected default state.
            close(sock);
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        if (apk_size < 0) {
            LOGE("preAppSpecialize: companion reported error (size=%lld)",
                 static_cast<long long>(apk_size));
            close(sock);
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        apk_fd = recv_fd(sock);
        close(sock);
        if (apk_fd < 0) {
            LOGE("preAppSpecialize: failed to receive APK fd");
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        LOGI("preAppSpecialize: enabled target %s (uid=%d), received APK fd=%d size=%lld",
             process_name.c_str(), args->uid, apk_fd, static_cast<long long>(apk_size));
    }

    void postAppSpecialize(const AppSpecializeArgs* /*args*/) override {
        if (apk_fd < 0) {
            return;
        }
        if (module_classloader) {
            LOGW("postAppSpecialize: module class loader is already initialized");
            close(apk_fd);
            apk_fd = -1;
            return;
        }

        LOGI("postAppSpecialize: installing WeKit module");

        // 1. Move the root-opened payload into this process's own read-only
        // memfd. The retained descriptor keeps PathClassLoader, resources, and
        // APK native-library lookups valid for the rest of the process.
        const int payload_fd = copy_apk_to_memfd(apk_fd, apk_size);
        close(apk_fd);
        apk_fd = payload_fd;
        if (apk_fd < 0) {
            LOGE("postAppSpecialize: failed to prepare APK payload");
            return;
        }
        const std::string apk_path = fd_path(apk_fd);

        // 2. Initialize ART hook subsystem.
        if (!art_hook_init(env)) {
            LOGE("postAppSpecialize: art_hook_init failed");
            close_payload_fd();
            return;
        }

        // 3. Load DEX from APK into JVM.
        jobject classloader = nullptr;
        if (!load_dex_from_apk(env, apk_path, classloader)) {
            LOGE("postAppSpecialize: failed to load DEX from APK");
            close_payload_fd();
            return;
        }

        // FunBox trusts its loader DEX before loading any bootstrap classes.
        // Do the equivalent for every DexFile owned by this PathClassLoader.
        if (!art_trust_class_loader(env, classloader)) {
            LOGE("postAppSpecialize: failed to trust module class loader");
            env->DeleteGlobalRef(classloader);
            close_payload_fd();
            return;
        }

        // The Zygisk SO is already resident in this process. Register its JNI
        // entry points against the dynamically loaded APK class instead of
        // calling System.load() from that class loader a second time.
        if (!register_hook_bridge_natives(env, classloader)) {
            LOGE("postAppSpecialize: failed to register Zygisk JNI methods");
            env->DeleteGlobalRef(classloader);
            close_payload_fd();
            return;
        }

        // 4. Find ZygiskEntry in the loaded classloader.
        jclass ze_class = load_class_from(env, classloader,
            "dev.ujhhgtg.wekit.loader.entry.zygisk.ZygiskEntry");
        if (!ze_class) {
            LOGE("postAppSpecialize: ZygiskEntry class not found");
            // BUG-3/4 fix: always release classloader global ref on early exit.
            env->DeleteGlobalRef(classloader);
            close_payload_fd();
            return;
        }

        // 5. Call ZygiskEntry.init(apkPath, dataDir, soPath, targetPackage).
        // Module startup receives the real host ClassLoader later from
        // createAppFactory, so do not replace the host main thread's context
        // ClassLoader here.
        jmethodID init_method = env->GetStaticMethodID(
            ze_class, "init",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        if (!init_method) {
            env->ExceptionClear();
            LOGE("postAppSpecialize: ZygiskEntry.init not found");
            env->DeleteLocalRef(ze_class);
            env->DeleteGlobalRef(classloader);
            close_payload_fd();
            return;
        }

        std::string so_path = get_self_so_path();

        // I-08 fix: hide SO path BEFORE ZygiskEntry.init so WeChat's native
        // anti-detection scan (which may run during Application.onCreate) never
        // sees the module path in /proc/self/maps.
        so_hide_path(so_path.c_str());

        jstring j_apk  = env->NewStringUTF(apk_path.c_str());
        jstring j_data = env->NewStringUTF(data_dir.c_str());
        jstring j_so   = env->NewStringUTF(so_path.c_str());
        jstring j_target = env->NewStringUTF(target_package.c_str());

        env->CallStaticVoidMethod(ze_class, init_method, j_apk, j_data, j_so, j_target);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            LOGE("postAppSpecialize: ZygiskEntry.init threw an exception");
        } else {
            LOGI("postAppSpecialize: ZygiskEntry.init completed");
        }

        env->DeleteLocalRef(j_apk);
        env->DeleteLocalRef(j_data);
        env->DeleteLocalRef(j_so);
        env->DeleteLocalRef(j_target);
        env->DeleteLocalRef(ze_class);

        // The native trampoline only retains raw ArtMethod pointers. Keep the
        // PathClassLoader rooted for the whole process so generated bridge
        // classes and the module DEX cannot be collected underneath it.
        module_classloader = classloader;
    }

    void preServerSpecialize(ServerSpecializeArgs* /*args*/) override {
        api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }

private:
    Api*    api      = nullptr;
    JNIEnv* env      = nullptr;
    int     apk_fd   = -1;
    int64_t apk_size = 0;
    std::string data_dir;
    std::string target_package;
    jobject module_classloader = nullptr;

    void close_payload_fd() {
        if (apk_fd >= 0) {
            close(apk_fd);
            apk_fd = -1;
        }
    }

    // BUG-13 fix: guard against null cl_class from FindClass.
    static jclass load_class_from(JNIEnv* e, jobject cl, const char* class_name) {
        jclass cl_class = e->FindClass("java/lang/ClassLoader");
        if (!cl_class) { e->ExceptionClear(); return nullptr; }
        jmethodID load_class_mid = e->GetMethodID(cl_class, "loadClass",
            "(Ljava/lang/String;)Ljava/lang/Class;");
        e->DeleteLocalRef(cl_class);
        if (!load_class_mid) { e->ExceptionClear(); return nullptr; }
        jstring name = e->NewStringUTF(class_name);
        auto cls = static_cast<jclass>(e->CallObjectMethod(cl, load_class_mid, name));
        e->DeleteLocalRef(name);
        if (e->ExceptionCheck()) { e->ExceptionClear(); return nullptr; }
        return cls;
    }

    static bool register_hook_bridge_natives(JNIEnv* e, jobject cl) {
        jclass bridge_class = load_class_from(
            e, cl, "dev.ujhhgtg.wekit.loader.entry.zygisk.ZygiskHookBridge");
        if (!bridge_class) return false;

        JNINativeMethod methods[] = {
            {const_cast<char*>("nativeGetArtMethod"),
             const_cast<char*>("(Ljava/lang/reflect/Executable;)J"),
             reinterpret_cast<void*>(
                 Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ZygiskHookBridge_nativeGetArtMethod)},
            {const_cast<char*>("nativeHookMethod"), const_cast<char*>("(JJJJ)I"),
             reinterpret_cast<void*>(
                 Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ZygiskHookBridge_nativeHookMethod)},
            {const_cast<char*>("nativeUnhookMethod"), const_cast<char*>("(JJ)I"),
             reinterpret_cast<void*>(
                 Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ZygiskHookBridge_nativeUnhookMethod)},
            {const_cast<char*>("nativeTrustClassLoader"),
             const_cast<char*>("(Ljava/lang/ClassLoader;)Z"),
             reinterpret_cast<void*>(
                 Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ZygiskHookBridge_nativeTrustClassLoader)},
            {const_cast<char*>("nativeAllocateInstance"),
             const_cast<char*>("(Ljava/lang/Class;)Ljava/lang/Object;"),
             reinterpret_cast<void*>(
                 Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ZygiskHookBridge_nativeAllocateInstance)},
            {const_cast<char*>("nativeHideLoadedModuleLibraries"), const_cast<char*>("()Z"),
             reinterpret_cast<void*>(
                 Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ZygiskHookBridge_nativeHideLoadedModuleLibraries)},
        };
        const jint result = e->RegisterNatives(
            bridge_class, methods, static_cast<jint>(sizeof(methods) / sizeof(methods[0])));
        if (result != JNI_OK || e->ExceptionCheck()) {
            e->ExceptionClear();
            e->DeleteLocalRef(bridge_class);
            return false;
        }
        e->DeleteLocalRef(bridge_class);
        return true;
    }

};

// ─── JNI exports (libwekit.so → ZygiskHookBridge) ────────────────────────────

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ZygiskHookBridge_nativeGetArtMethod(
    JNIEnv* env, jclass /*clazz*/, jobject executable)
{
    return static_cast<jlong>(art_get_art_method(env, executable));
}

JNIEXPORT jint JNICALL
Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ZygiskHookBridge_nativeHookMethod(
    JNIEnv* env, jclass /*clazz*/,
    jlong target_art, jlong backup_art, jlong bridge_art, jlong /*hook_id*/)
{
    if (!art_hook_is_initialized()) {
        if (!art_hook_init(env)) return -1;
    }
    return art_hook_method(env,
        static_cast<uintptr_t>(target_art),
        static_cast<uintptr_t>(backup_art),
        static_cast<uintptr_t>(bridge_art)) ? 0 : -1;
}

JNIEXPORT jint JNICALL
Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ZygiskHookBridge_nativeUnhookMethod(
    JNIEnv* env, jclass /*clazz*/,
    jlong target_art, jlong backup_art)
{
    return art_unhook_method(env,
        static_cast<uintptr_t>(target_art),
        static_cast<uintptr_t>(backup_art)) ? 0 : -1;
}

JNIEXPORT jboolean JNICALL
Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ZygiskHookBridge_nativeTrustClassLoader(
    JNIEnv* env, jclass /*clazz*/, jobject class_loader)
{
    if (!art_hook_is_initialized() && !art_hook_init(env)) return JNI_FALSE;
    return art_trust_class_loader(env, class_loader) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ZygiskHookBridge_nativeAllocateInstance(
    JNIEnv* env, jclass /*clazz*/, jclass target_class)
{
    return target_class ? env->AllocObject(target_class) : nullptr;
}

JNIEXPORT jboolean JNICALL
Java_dev_ujhhgtg_wekit_loader_entry_zygisk_ZygiskHookBridge_nativeHideLoadedModuleLibraries(
    JNIEnv* /*env*/, jclass /*clazz*/)
{
    // NativeLoader has completed at this point. Hide every module-provided
    // native library that can have been added after the early loader-SO pass.
    const int dexkit = so_hide_path("libdexkit.so");
    const int wekit_native = so_hide_path("libwekit_native.so");
    const int mmkv = so_hide_path("libmmkv.so");
    return dexkit >= 0 && wekit_native >= 0 && mmkv >= 0 ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"

REGISTER_ZYGISK_MODULE(WekitZygisk)
REGISTER_ZYGISK_COMPANION(companion_handler)
