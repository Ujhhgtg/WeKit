#define _GNU_SOURCE
#include <dirent.h>
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/memfd.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#define MAX_HTTP_BODY (64U * 1024U)
#define BOOTSTRAP_CERT_SHA256 "6bc3be50d46327b8c66b564ff946a9c51bbb72a198fc7cd651e9726901584315"
#define LOADER_API_ORIGIN "https://auth.wekit.ujhhgtg.dev"
#define LOADER_TELEGRAM_BOT "ujhhgtg_wekit_bot"
#define LOADER_RELEASE_KEY_ID "release-20261011-1"
#define LOADER_RELEASE_KEY "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE_5JoFKeoY989hXH2Vj5ZTtl7Az2QB63WSDpjkn-0K6LLfS0KZYa-v-n7rpuSQ6WUNU-w3VwqNtp6lhpY77BKqw"
#define BODY_MAGIC "WKX1"
#define LOADER_TLS_PIN_COUNT 5
static const char *const LOADER_TLS_PINS[LOADER_TLS_PIN_COUNT] = {
    "hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Gc=",
    "QXnt2YHvdHR3tJYmQIr0Paosp6t/nggsEGD4QJZ3Q0g=",
    "mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c=",
    "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=",
    "diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=",
};

static const unsigned char BODY_XOR_SEED[32] = {
    0xeb,0x6f,0xf0,0x9b,0x17,0xb8,0xc5,0x39,0x39,0xa9,0xa3,0x12,0x22,0xff,0xb9,0x13,
    0x33,0x0c,0x02,0x83,0x56,0x67,0x5e,0xc7,0x9e,0x4d,0x46,0xc1,0x88,0x95,0x4e,0x2f,
};
static const unsigned char BODY_CENTER_SELECTOR[32] = {
    0x25,0xbe,0xe2,0x86,0xf5,0xd7,0x79,0xea,0xb4,0x65,0xef,0x61,0x58,0x15,0x8b,0xda,
    0x83,0xb6,0x82,0x3a,0x0f,0x54,0x39,0x0f,0x85,0x6f,0x7f,0xa9,0x63,0x84,0xd6,0x29,
};
static const unsigned char BODY_CENTER_SEED[32] = {
    0x73,0x83,0xf0,0xb4,0x23,0x2b,0x2e,0x76,0x63,0x5b,0x26,0x04,0x14,0xf2,0x99,0x94,
    0x8a,0x9f,0x37,0x46,0x93,0x8e,0x2b,0xfa,0x24,0x51,0x5a,0x0f,0x74,0xc7,0xf3,0x00,
};
static const unsigned char BODY_STRIDE_SELECTOR[32] = {
    0xd5,0x95,0xff,0x8b,0x81,0x85,0x97,0x4a,0x6e,0x5b,0x39,0x75,0x25,0xb3,0xe6,0x40,
    0x94,0xda,0xbf,0x4e,0x66,0xcb,0x10,0xae,0x24,0xfe,0xf7,0x29,0x51,0x39,0xb0,0xb0,
};
static const unsigned char BODY_STRIDE_SEED[32] = {
    0xde,0xa0,0xb9,0x10,0x9d,0x47,0x66,0x27,0x5a,0xc1,0xb9,0xfa,0x2e,0x48,0xf0,0x4d,
    0x13,0xbc,0x94,0x31,0xb3,0x42,0x2e,0x9b,0x8c,0x3a,0x1b,0xd2,0x22,0x75,0x59,0x9f,
};

static unsigned char body_stream(const unsigned char *seed, size_t length, uint32_t tag, size_t index) {
    uint64_t x = ((uint64_t)length * 0x9e3779b97f4a7c15ULL) ^ ((uint64_t)tag << 29);
    for (size_t i = 0; i < 8; ++i) x ^= (uint64_t)seed[(i * 5 + 3) & 31] << (i * 8);
    x ^= (uint64_t)index * 0xd6e8feb86659fd93ULL;
    for (unsigned int round = 0; round < 8; ++round) {
        x ^= x << 7; x ^= x >> 9; x *= 0xd6e8feb86659fd93ULL;
        x = (x >> ((seed[(index + round) & 31] & 0x3f))) | (x << ((64 - (seed[(index + round) & 31] & 0x3f)) & 63));
    }
    return (unsigned char)(x ^ seed[index & 31] ^ (unsigned char)(index * 99));
}

static size_t body_gcd(size_t a, size_t b) { while (b != 0) { size_t t = a % b; a = b; b = t; } return a; }
static void body_selector(const unsigned char *selector, size_t length, size_t offset, size_t *step, size_t *start) {
    *step = (((size_t)selector[offset] | ((size_t)selector[offset + 1] << 8)) % length) | 1;
    while (body_gcd(*step, length) != 1) { *step = (*step + 2) % length; if (*step < 2) *step = 1; }
    *start = ((size_t)selector[offset + 2] | ((size_t)selector[offset + 3] << 8)) % length;
}
static unsigned char body_rotl(unsigned char value, unsigned int count) { return (unsigned char)((value << (count & 7)) | (value >> ((8 - count) & 7))); }
static unsigned char body_rotr(unsigned char value, unsigned int count) { return body_rotl(value, 8 - (count & 7)); }

static void body_xor(unsigned char *data, size_t length) {
    for (size_t i = 0; i < length; ++i) data[i] ^= body_stream(BODY_XOR_SEED, length, 0x1c1f7500, i);
}
static void body_center(unsigned char *data, size_t length) {
    if (length == 0) return;
    size_t step, start; body_selector(BODY_CENTER_SELECTOR, length, 0, &step, &start);
    for (size_t index = 0; index < length; ++index) {
        size_t center = (index & 1) == 0 ? (length + length / 2 - ((index / 2 + 1) % length)) % length : (index / 2 + length / 2) % length;
        size_t position = (start + center * step) % length;
        data[position] ^= BODY_CENTER_SELECTOR[(position + index) & 31] ^ (unsigned char)(index * 0x5d) ^ body_rotl((unsigned char)position, (unsigned int)(index & 7)) ^ body_stream(BODY_CENTER_SEED, length, 0x6d3a5b17, index);
    }
}
static void body_stride(unsigned char *data, size_t length) {
    if (length == 0) return;
    size_t step, start; body_selector(BODY_STRIDE_SELECTOR, length, 8, &step, &start);
    for (size_t index = 0; index < length; ++index) {
        size_t position = (start + index * step) % length;
        data[position] ^= body_stream(BODY_STRIDE_SEED, length, 0x2e4981c3, index) ^ (unsigned char)(index * 0xa7) ^ body_rotr((unsigned char)position, (unsigned int)(index & 7)) ^ (unsigned char)(BODY_STRIDE_SELECTOR[(position ^ index) & 31] + (unsigned char)position);
    }
}
static void body_transform(unsigned char *data, size_t length, int encode) {
    if (encode) { body_xor(data, length); body_center(data, length); body_stride(data, length); }
    else { body_stride(data, length); body_center(data, length); body_xor(data, length); }
}

static void throw_class(JNIEnv *env, const char *name, const char *message) {
    jclass type = (*env)->FindClass(env, name);
    if (type != NULL) { (*env)->ThrowNew(env, type, message); (*env)->DeleteLocalRef(env, type); }
}
static void throw_io(JNIEnv *env, const char *operation) {
    char message[256];
    snprintf(message, sizeof(message), "%s: errno=%d (%s)", operation, errno, strerror(errno));
    throw_class(env, "java/io/IOException", message);
}
static void throw_java_io(JNIEnv *env, const char *operation) {
    jthrowable throwable = (*env)->ExceptionOccurred(env);
    if (throwable == NULL) { throw_io(env, operation); return; }
    (*env)->ExceptionClear(env);
    jclass throwable_type = (*env)->FindClass(env, "java/lang/Throwable");
    jmethodID message_method = throwable_type == NULL ? NULL : (*env)->GetMethodID(env, throwable_type, "getMessage", "()Ljava/lang/String;");
    jmethodID string_method = throwable_type == NULL ? NULL : (*env)->GetMethodID(env, throwable_type, "toString", "()Ljava/lang/String;");
    jstring message = message_method == NULL ? NULL : (jstring)(*env)->CallObjectMethod(env, throwable, message_method);
    if (message == NULL && string_method != NULL) message = (jstring)(*env)->CallObjectMethod(env, throwable, string_method);
    const char *text = message == NULL ? NULL : (*env)->GetStringUTFChars(env, message, NULL);
    char combined[512];
    snprintf(combined, sizeof(combined), "%s: %s", operation, text == NULL ? "Java exception" : text);
    if (text != NULL) (*env)->ReleaseStringUTFChars(env, message, text);
    if (message != NULL) (*env)->DeleteLocalRef(env, message);
    if (throwable_type != NULL) (*env)->DeleteLocalRef(env, throwable_type);
    (*env)->DeleteLocalRef(env, throwable);
    throw_class(env, "java/io/IOException", combined);
}
static int has_exception(JNIEnv *env) { return (*env)->ExceptionCheck(env) != JNI_FALSE; }
static void clear_exception(JNIEnv *env) { if (has_exception(env)) (*env)->ExceptionClear(env); }

static int runtime_guard(void) {
    FILE *maps = fopen("/proc/self/maps", "re");
    if (maps == NULL) return -1;
    char line[PATH_MAX + 256];
    while (fgets(line, sizeof(line), maps) != NULL) {
        if (strstr(line, "frida") != NULL || strstr(line, "gum-js-loop") != NULL) { fclose(maps); return 1; }
    }
    if (ferror(maps) != 0) { fclose(maps); return -1; }
    fclose(maps);
    DIR *tasks = opendir("/proc/self/task");
    if (tasks == NULL) return -1;
    struct dirent *entry; errno = 0;
    while ((entry = readdir(tasks)) != NULL) {
        if (entry->d_name[0] == '.') continue;
        char path[PATH_MAX];
        int size = snprintf(path, sizeof(path), "/proc/self/task/%s/comm", entry->d_name);
        if (size <= 0 || (size_t)size >= sizeof(path)) { closedir(tasks); return -1; }
        FILE *comm = fopen(path, "re");
        if (comm == NULL) { closedir(tasks); return -1; }
        char name[128]; int read = fgets(name, sizeof(name), comm) != NULL; int failed = ferror(comm) != 0;
        fclose(comm);
        if (failed || !read) { closedir(tasks); return -1; }
        name[strcspn(name, "\r\n")] = '\0';
        if (strstr(name, "frida") != NULL || strstr(name, "gum-js-loop") != NULL) { closedir(tasks); return 1; }
    }
    int failed = errno != 0; closedir(tasks); return failed ? -1 : 0;
}

static int string_equals(JNIEnv *env, jstring value, const char *expected) {
    if (value == NULL) return 0;
    const char *actual = (*env)->GetStringUTFChars(env, value, NULL);
    if (actual == NULL) return 0;
    int equal = strcmp(actual, expected) == 0;
    (*env)->ReleaseStringUTFChars(env, value, actual);
    return equal;
}

static int archive_is_signed_wekit(JNIEnv *env, jobject context, jstring path) {
    int valid = 0;
    jclass context_type = (*env)->GetObjectClass(env, context);
    if (context_type == NULL) return 0;
    jmethodID get_pm = (*env)->GetMethodID(env, context_type, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    jobject pm = get_pm == NULL ? NULL : (*env)->CallObjectMethod(env, context, get_pm);
    if (has_exception(env) || pm == NULL) { clear_exception(env); goto done_context; }
    jclass pm_type = (*env)->GetObjectClass(env, pm);
    jmethodID archive = (*env)->GetMethodID(env, pm_type, "getPackageArchiveInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
    jobject info = archive == NULL ? NULL : (*env)->CallObjectMethod(env, pm, archive, path, 0x08000000);
    if (has_exception(env) || info == NULL) { clear_exception(env); goto done_pm; }
    jclass info_type = (*env)->FindClass(env, "android/content/pm/PackageInfo");
    jfieldID package_name = info_type == NULL ? NULL : (*env)->GetFieldID(env, info_type, "packageName", "Ljava/lang/String;");
    jfieldID signing_info = info_type == NULL ? NULL : (*env)->GetFieldID(env, info_type, "signingInfo", "Landroid/content/pm/SigningInfo;");
    jstring name = package_name == NULL ? NULL : (jstring)(*env)->GetObjectField(env, info, package_name);
    jobject signing = signing_info == NULL ? NULL : (*env)->GetObjectField(env, info, signing_info);
    jstring expected_cert = (*env)->NewStringUTF(env, BOOTSTRAP_CERT_SHA256);
    valid = package_name != NULL && signing_info != NULL && signing != NULL && string_equals(env, name, "dev.ujhhgtg.wekit");
    if (valid) {
        jclass signing_type = (*env)->FindClass(env, "android/content/pm/SigningInfo");
        jmethodID method = signing_type == NULL ? NULL : (*env)->GetMethodID(env, signing_type, "getApkContentsSigners", "()[Landroid/content/pm/Signature;");
        jobjectArray signers = method == NULL ? NULL : (jobjectArray)(*env)->CallObjectMethod(env, signing, method);
        valid = !has_exception(env) && signers != NULL && (*env)->GetArrayLength(env, signers) > 0;
        if (valid && expected_cert != NULL && (*env)->GetStringLength(env, expected_cert) > 0) {
            jobject signer = (*env)->GetObjectArrayElement(env, signers, 0);
            jclass signature_type = (*env)->FindClass(env, "android/content/pm/Signature");
            jmethodID bytes_method = signature_type == NULL ? NULL : (*env)->GetMethodID(env, signature_type, "toByteArray", "()[B");
            jbyteArray der = bytes_method == NULL ? NULL : (jbyteArray)(*env)->CallObjectMethod(env, signer, bytes_method);
            jclass digest_type = (*env)->FindClass(env, "java/security/MessageDigest");
            jmethodID instance = digest_type == NULL ? NULL : (*env)->GetStaticMethodID(env, digest_type, "getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;");
            jmethodID digest_method = digest_type == NULL ? NULL : (*env)->GetMethodID(env, digest_type, "digest", "([B)[B");
            jstring algorithm = (*env)->NewStringUTF(env, "SHA-256");
            jobject digest = (instance == NULL || algorithm == NULL) ? NULL : (*env)->CallStaticObjectMethod(env, digest_type, instance, algorithm);
            jbyteArray hash = (digest == NULL || der == NULL || digest_method == NULL) ? NULL : (jbyteArray)(*env)->CallObjectMethod(env, digest, digest_method, der);
            char actual[65] = {0};
            if (!has_exception(env) && hash != NULL && (*env)->GetArrayLength(env, hash) == 32) {
                jbyte bytes[32]; (*env)->GetByteArrayRegion(env, hash, 0, 32, bytes);
                for (int i = 0; i < 32; ++i) snprintf(actual + i * 2, 3, "%02x", (unsigned char)bytes[i]);
                const char *wanted = (*env)->GetStringUTFChars(env, expected_cert, NULL);
                char normalized[65] = {0}; size_t cursor = 0;
                if (wanted != NULL) { for (size_t i = 0; wanted[i] != '\0' && cursor < sizeof(normalized) - 1; ++i) if (wanted[i] != ':') normalized[cursor++] = (char)tolower((unsigned char)wanted[i]); (*env)->ReleaseStringUTFChars(env, expected_cert, wanted); }
                valid = cursor == 64 && strcmp(actual, normalized) == 0;
            } else valid = 0;
            clear_exception(env);
            if (hash != NULL) (*env)->DeleteLocalRef(env, hash); if (digest != NULL) (*env)->DeleteLocalRef(env, digest);
            if (algorithm != NULL) (*env)->DeleteLocalRef(env, algorithm); if (digest_type != NULL) (*env)->DeleteLocalRef(env, digest_type);
            if (der != NULL) (*env)->DeleteLocalRef(env, der); if (signature_type != NULL) (*env)->DeleteLocalRef(env, signature_type); if (signer != NULL) (*env)->DeleteLocalRef(env, signer);
        }
        if (signers != NULL) (*env)->DeleteLocalRef(env, signers);
        if (signing_type != NULL) (*env)->DeleteLocalRef(env, signing_type);
    }
    clear_exception(env);
    if (name != NULL) (*env)->DeleteLocalRef(env, name);
    if (expected_cert != NULL) (*env)->DeleteLocalRef(env, expected_cert);
    if (signing != NULL) (*env)->DeleteLocalRef(env, signing);
    if (info_type != NULL) (*env)->DeleteLocalRef(env, info_type);
    (*env)->DeleteLocalRef(env, info);
done_pm:
    (*env)->DeleteLocalRef(env, pm_type); (*env)->DeleteLocalRef(env, pm);
done_context:
    (*env)->DeleteLocalRef(env, context_type); return valid;
}

static int valid_apk_path(const char *path) {
    if (path == NULL || path[0] != '/') return 0;
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW); if (fd < 0) return 0;
    struct stat info;
    int valid = fstat(fd, &info) == 0 && S_ISREG(info.st_mode) && info.st_size > 0 && info.st_size <= 128 * 1024 * 1024;
    close(fd); return valid;
}

static jint loader_create(JNIEnv *env, jobject receiver) {
    (void)receiver;
    if (runtime_guard() != 0) {
        throw_class(env, "java/lang/SecurityException", "Frida runtime detected or guard unavailable");
        return -1;
    }
    int fd = (int)syscall(SYS_memfd_create, "wekit-payload", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (fd < 0) throw_io(env, "memfd_create");
    return fd;
}

static jobjectArray loader_configuration(JNIEnv *env, jobject receiver) {
    (void)receiver;
    static const char *values[] = {
        LOADER_API_ORIGIN, LOADER_TELEGRAM_BOT, LOADER_RELEASE_KEY_ID, LOADER_RELEASE_KEY,
        "hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Gc=",
        "QXnt2YHvdHR3tJYmQIr0Paosp6t/nggsEGD4QJZ3Q0g=",
        "mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c=",
        "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=",
        "diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=",
    };
    jclass string_type = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = string_type == NULL ? NULL : (*env)->NewObjectArray(env, 9, string_type, NULL);
    if (result == NULL) return NULL;
    for (jsize i = 0; i < 9; ++i) {
        jstring value = (*env)->NewStringUTF(env, values[i]);
        if (value == NULL) return NULL;
        (*env)->SetObjectArrayElement(env, result, i, value);
        (*env)->DeleteLocalRef(env, value);
    }
    (*env)->DeleteLocalRef(env, string_type);
    return result;
}
static void loader_seal(JNIEnv *env, jobject receiver, jint fd) {
    (void)receiver; const int seals = F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL;
    if (fcntl(fd, F_ADD_SEALS, seals) < 0) { throw_io(env, "F_ADD_SEALS"); return; }
    int actual = fcntl(fd, F_GET_SEALS); if (actual < 0) { throw_io(env, "F_GET_SEALS"); return; }
    if ((actual & seals) != seals) throw_class(env, "java/io/IOException", "memfd seals missing");
}
static jboolean loader_verify_bootstrap(JNIEnv *env, jobject receiver, jobject context, jstring path) {
    (void)receiver; int frida = runtime_guard();
    if (frida != 0) { throw_class(env, "java/lang/SecurityException", frida > 0 ? "Frida runtime detected" : "Frida guard unavailable"); return JNI_FALSE; }
    if (context == NULL || path == NULL) { throw_class(env, "java/lang/SecurityException", "invalid bootstrap arguments"); return JNI_FALSE; }
    const char *text = (*env)->GetStringUTFChars(env, path, NULL); if (text == NULL) return JNI_FALSE;
    int valid = valid_apk_path(text); (*env)->ReleaseStringUTFChars(env, path, text);
    if (!valid || !archive_is_signed_wekit(env, context, path)) { throw_class(env, "java/lang/SecurityException", "bootstrap APK signature or identity rejected"); return JNI_FALSE; }
    return JNI_TRUE;
}

static int append_body(unsigned char **buffer, size_t *length, size_t *capacity, const unsigned char *bytes, size_t count) {
    if (count > MAX_HTTP_BODY - *length) return 0;
    if (*length + count > *capacity) {
        size_t next = *capacity == 0 ? 8192 : *capacity; while (next < *length + count) next *= 2;
        unsigned char *grown = realloc(*buffer, next); if (grown == NULL) return 0;
        *buffer = grown; *capacity = next;
    }
    memcpy(*buffer + *length, bytes, count); *length += count; return 1;
}

static int verify_pins(JNIEnv *env, jobject connection) {
    jclass https = (*env)->FindClass(env, "javax/net/ssl/HttpsURLConnection");
    jmethodID certs = https == NULL ? NULL : (*env)->GetMethodID(env, https, "getServerCertificates", "()[Ljava/security/cert/Certificate;");
    jobjectArray chain = certs == NULL ? NULL : (jobjectArray)(*env)->CallObjectMethod(env, connection, certs);
    if (has_exception(env) || chain == NULL) { clear_exception(env); return 0; }
    jclass cert_type = (*env)->FindClass(env, "java/security/cert/Certificate");
    jclass key_type = (*env)->FindClass(env, "java/security/PublicKey");
    jclass digest_type = (*env)->FindClass(env, "java/security/MessageDigest");
    jclass base64_type = (*env)->FindClass(env, "android/util/Base64");
    jmethodID get_key = cert_type == NULL ? NULL : (*env)->GetMethodID(env, cert_type, "getPublicKey", "()Ljava/security/PublicKey;");
    jmethodID encoded = key_type == NULL ? NULL : (*env)->GetMethodID(env, key_type, "getEncoded", "()[B");
    jmethodID get_digest = digest_type == NULL ? NULL : (*env)->GetStaticMethodID(env, digest_type, "getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    jmethodID digest = digest_type == NULL ? NULL : (*env)->GetMethodID(env, digest_type, "digest", "([B)[B");
    jmethodID decode = base64_type == NULL ? NULL : (*env)->GetStaticMethodID(env, base64_type, "decode", "(Ljava/lang/String;I)[B");
    jstring algorithm = (*env)->NewStringUTF(env, "SHA-256");
    jobject hasher = get_digest == NULL ? NULL : (*env)->CallStaticObjectMethod(env, digest_type, get_digest, algorithm);
    int valid = !has_exception(env) && get_key != NULL && encoded != NULL && digest != NULL && decode != NULL && hasher != NULL;
    int matched = 0;
    for (jsize i = 0; valid && i < (*env)->GetArrayLength(env, chain); ++i) {
        jobject cert = (*env)->GetObjectArrayElement(env, chain, i);
        jobject key = cert == NULL ? NULL : (*env)->CallObjectMethod(env, cert, get_key);
        jbyteArray key_bytes = key == NULL ? NULL : (jbyteArray)(*env)->CallObjectMethod(env, key, encoded);
        jbyteArray actual = key_bytes == NULL ? NULL : (jbyteArray)(*env)->CallObjectMethod(env, hasher, digest, key_bytes);
        if (has_exception(env) || actual == NULL) { clear_exception(env); valid = 0; }
        for (jsize p = 0; valid && p < LOADER_TLS_PIN_COUNT; ++p) {
            jstring pin = (*env)->NewStringUTF(env, LOADER_TLS_PINS[p]);
            jbyteArray expected = pin == NULL ? NULL : (jbyteArray)(*env)->CallStaticObjectMethod(env, base64_type, decode, pin, 2);
            if (has_exception(env) || expected == NULL) { clear_exception(env); valid = 0; }
            else if ((*env)->GetArrayLength(env, expected) == 32 && (*env)->GetArrayLength(env, actual) == 32) {
                jbyte left[32], right[32]; (*env)->GetByteArrayRegion(env, actual, 0, 32, left); (*env)->GetByteArrayRegion(env, expected, 0, 32, right);
                if (memcmp(left, right, 32) == 0) matched = 1;
            }
            if (expected != NULL) (*env)->DeleteLocalRef(env, expected); if (pin != NULL) (*env)->DeleteLocalRef(env, pin);
        }
        if (actual != NULL) (*env)->DeleteLocalRef(env, actual); if (key_bytes != NULL) (*env)->DeleteLocalRef(env, key_bytes);
        if (key != NULL) (*env)->DeleteLocalRef(env, key); if (cert != NULL) (*env)->DeleteLocalRef(env, cert);
    }
    if (hasher != NULL) (*env)->DeleteLocalRef(env, hasher); if (algorithm != NULL) (*env)->DeleteLocalRef(env, algorithm);
    if (base64_type != NULL) (*env)->DeleteLocalRef(env, base64_type); if (digest_type != NULL) (*env)->DeleteLocalRef(env, digest_type);
    if (key_type != NULL) (*env)->DeleteLocalRef(env, key_type); if (cert_type != NULL) (*env)->DeleteLocalRef(env, cert_type);
    (*env)->DeleteLocalRef(env, chain); if (https != NULL) (*env)->DeleteLocalRef(env, https); return valid && matched;
}

static jbyteArray loader_request(JNIEnv *env, jobject receiver, jstring path_text, jstring method_text,
                                 jobjectArray header_text, jbyteArray body) {
    (void)receiver; jbyteArray result = NULL; jbyteArray transformed_body = NULL;
    if (runtime_guard() != 0) {
        throw_class(env, "java/lang/SecurityException", "Frida runtime detected or guard unavailable");
        return NULL;
    }
    const char *path = path_text == NULL ? NULL : (*env)->GetStringUTFChars(env, path_text, NULL);
    if (path == NULL || strncmp(path, "/v1/", 4) != 0 || strchr(path, '?') != NULL || strchr(path, '#') != NULL) {
        if (path != NULL) (*env)->ReleaseStringUTFChars(env, path_text, path);
        throw_class(env, "java/lang/SecurityException", "native loader rejected HTTP path"); return NULL;
    }
    char url_buffer[1024];
    int url_length = snprintf(url_buffer, sizeof(url_buffer), "%s%s", LOADER_API_ORIGIN, path);
    (*env)->ReleaseStringUTFChars(env, path_text, path);
    if (url_length <= 0 || (size_t)url_length >= sizeof(url_buffer)) { throw_class(env, "java/lang/IllegalArgumentException", "native loader path too long"); return NULL; }
    jstring url_text = (*env)->NewStringUTF(env, url_buffer);
    jclass url_type = (*env)->FindClass(env, "java/net/URL");
    jclass http_type = (*env)->FindClass(env, "java/net/HttpURLConnection");
    jclass connection_type = (*env)->FindClass(env, "java/net/URLConnection");
    jmethodID constructor = url_type == NULL ? NULL : (*env)->GetMethodID(env, url_type, "<init>", "(Ljava/lang/String;)V");
    jobject url = constructor == NULL ? NULL : (*env)->NewObject(env, url_type, constructor, url_text);
    jmethodID open = url_type == NULL ? NULL : (*env)->GetMethodID(env, url_type, "openConnection", "()Ljava/net/URLConnection;");
    jobject connection = (url == NULL || open == NULL) ? NULL : (*env)->CallObjectMethod(env, url, open);
    if (has_exception(env) || connection == NULL) { clear_exception(env); throw_io(env, "open HTTPS connection"); goto cleanup; }
    jmethodID set_method = (*env)->GetMethodID(env, http_type, "setRequestMethod", "(Ljava/lang/String;)V");
    jmethodID redirects = (*env)->GetMethodID(env, http_type, "setInstanceFollowRedirects", "(Z)V");
    jmethodID response = (*env)->GetMethodID(env, http_type, "getResponseCode", "()I");
    jmethodID timeout = (*env)->GetMethodID(env, connection_type, "setConnectTimeout", "(I)V");
    jmethodID read_timeout = (*env)->GetMethodID(env, connection_type, "setReadTimeout", "(I)V");
    jmethodID property = (*env)->GetMethodID(env, connection_type, "setRequestProperty", "(Ljava/lang/String;Ljava/lang/String;)V");
    jmethodID do_output = (*env)->GetMethodID(env, connection_type, "setDoOutput", "(Z)V");
    if (set_method == NULL || redirects == NULL || response == NULL || timeout == NULL || read_timeout == NULL || property == NULL || do_output == NULL) { clear_exception(env); throw_io(env, "resolve HTTPS methods"); goto disconnect; }
    (*env)->CallVoidMethod(env, connection, timeout, 15000); (*env)->CallVoidMethod(env, connection, read_timeout, 30000);
    if (body != NULL && (*env)->GetArrayLength(env, body) > 0) (*env)->CallVoidMethod(env, connection, do_output, JNI_TRUE);
    (*env)->CallVoidMethod(env, connection, redirects, JNI_FALSE); (*env)->CallVoidMethod(env, connection, set_method, method_text);
    if (has_exception(env)) { clear_exception(env); throw_io(env, "configure HTTPS method"); goto disconnect; }
    jsize header_count = header_text == NULL ? 0 : (*env)->GetArrayLength(env, header_text);
    for (jsize i = 0; i < header_count; ++i) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, header_text, i);
        const char *text = item == NULL ? NULL : (*env)->GetStringUTFChars(env, item, NULL); const char *split = text == NULL ? NULL : strchr(text, ':');
        if (split == NULL || split == text) { if (text) (*env)->ReleaseStringUTFChars(env, item, text); if (item) (*env)->DeleteLocalRef(env, item); throw_class(env, "java/lang/IllegalArgumentException", "invalid HTTP header"); goto disconnect; }
        size_t size = (size_t)(split - text); if (size >= 128) { (*env)->ReleaseStringUTFChars(env, item, text); (*env)->DeleteLocalRef(env, item); throw_class(env, "java/lang/IllegalArgumentException", "HTTP header too long"); goto disconnect; }
        char name[128]; memcpy(name, text, size); name[size] = '\0'; ++split; while (*split == ' ') ++split;
        jstring jname = (*env)->NewStringUTF(env, name); jstring jvalue = (*env)->NewStringUTF(env, split);
        (*env)->CallVoidMethod(env, connection, property, jname, jvalue); (*env)->ReleaseStringUTFChars(env, item, text);
        (*env)->DeleteLocalRef(env, item); (*env)->DeleteLocalRef(env, jname); (*env)->DeleteLocalRef(env, jvalue);
        if (has_exception(env)) { clear_exception(env); throw_io(env, "set HTTPS header"); goto disconnect; }
    }
    jstring transform_name = (*env)->NewStringUTF(env, "X-WeKit-Body-Transform");
    jstring transform_value = (*env)->NewStringUTF(env, "WKX1");
    if (transform_name == NULL || transform_value == NULL) { throw_io(env, "allocate body transform header"); goto disconnect; }
    (*env)->CallVoidMethod(env, connection, property, transform_name, transform_value);
    (*env)->DeleteLocalRef(env, transform_name); (*env)->DeleteLocalRef(env, transform_value);
    if (has_exception(env)) { clear_exception(env); throw_io(env, "set body transform header"); goto disconnect; }
    if (body != NULL && (*env)->GetArrayLength(env, body) > 0) {
        jsize clear_size = (*env)->GetArrayLength(env, body);
        unsigned char *clear = (unsigned char *)(*env)->GetByteArrayElements(env, body, NULL);
        if (clear == NULL) { throw_io(env, "read HTTP body"); goto disconnect; }
        transformed_body = (*env)->NewByteArray(env, clear_size + 4);
        if (transformed_body == NULL) { (*env)->ReleaseByteArrayElements(env, body, (jbyte *)clear, JNI_ABORT); goto disconnect; }
        jbyte magic[4] = {'W', 'K', 'X', '1'};
        (*env)->SetByteArrayRegion(env, transformed_body, 0, 4, magic);
        unsigned char *encoded = malloc((size_t)clear_size);
        if (encoded == NULL) { (*env)->ReleaseByteArrayElements(env, body, (jbyte *)clear, JNI_ABORT); throw_io(env, "allocate transformed HTTP body"); goto disconnect; }
        memcpy(encoded, clear, (size_t)clear_size);
        (*env)->ReleaseByteArrayElements(env, body, (jbyte *)clear, JNI_ABORT);
        body_transform(encoded, (size_t)clear_size, 1);
        (*env)->SetByteArrayRegion(env, transformed_body, 4, clear_size, (jbyte *)encoded);
        free(encoded);
        body = transformed_body;
        jmethodID output_method = (*env)->GetMethodID(env, connection_type, "getOutputStream", "()Ljava/io/OutputStream;");
        jclass output_type = (*env)->FindClass(env, "java/io/OutputStream"); jmethodID write = output_type == NULL ? NULL : (*env)->GetMethodID(env, output_type, "write", "([B)V");
        jobject stream = output_method == NULL ? NULL : (*env)->CallObjectMethod(env, connection, output_method);
        if (stream == NULL || write == NULL) { if (has_exception(env)) throw_java_io(env, "open HTTPS output"); else throw_io(env, "open HTTPS output"); if (stream) (*env)->DeleteLocalRef(env, stream); if (output_type) (*env)->DeleteLocalRef(env, output_type); goto disconnect; }
        (*env)->CallVoidMethod(env, stream, write, body); jmethodID close = (*env)->GetMethodID(env, output_type, "close", "()V"); if (close) (*env)->CallVoidMethod(env, stream, close);
        (*env)->DeleteLocalRef(env, stream); (*env)->DeleteLocalRef(env, output_type); if (has_exception(env)) { clear_exception(env); throw_io(env, "write HTTPS request"); goto disconnect; }
    }
    jint status = (*env)->CallIntMethod(env, connection, response);
    if (has_exception(env)) { clear_exception(env); throw_io(env, "read HTTPS response"); goto disconnect; }
    if (!verify_pins(env, connection)) { throw_class(env, "java/lang/SecurityException", "HTTPS pin mismatch"); goto disconnect; }
    const char *input_name = status >= 400 ? "getErrorStream" : "getInputStream"; jmethodID input_method = (*env)->GetMethodID(env, connection_type, input_name, "()Ljava/io/InputStream;");
    jobject input = input_method == NULL ? NULL : (*env)->CallObjectMethod(env, connection, input_method); jclass input_type = (*env)->FindClass(env, "java/io/InputStream"); jmethodID read = input_type == NULL ? NULL : (*env)->GetMethodID(env, input_type, "read", "([B)I"); jbyteArray chunk = read == NULL ? NULL : (*env)->NewByteArray(env, 8192);
    unsigned char *contents = NULL; size_t length = 0, capacity = 0;
    if (input != NULL && read != NULL && chunk != NULL) {
        for (;;) { jint bytes = (*env)->CallIntMethod(env, input, read, chunk); if (has_exception(env)) { clear_exception(env); throw_io(env, "read HTTPS body"); free(contents); contents = NULL; goto body_cleanup; } if (bytes < 0) break; if (bytes == 0) continue; jbyte block[8192]; (*env)->GetByteArrayRegion(env, chunk, 0, bytes, block); if (!append_body(&contents, &length, &capacity, (unsigned char *)block, (size_t)bytes)) { throw_io(env, "buffer HTTPS body"); free(contents); contents = NULL; goto body_cleanup; } }
    } else if (status < 400) { throw_io(env, "open HTTPS input"); goto body_cleanup; }
    if (length >= 4 && memcmp(contents, BODY_MAGIC, 4) == 0) {
        body_transform(contents + 4, length - 4, 0);
        memmove(contents, contents + 4, length - 4);
        length -= 4;
    }
    result = (*env)->NewByteArray(env, (jsize)(length + 4));
    if (result != NULL) { jbyte prefix[4] = {(jbyte)(status >> 24), (jbyte)(status >> 16), (jbyte)(status >> 8), (jbyte)status}; (*env)->SetByteArrayRegion(env, result, 0, 4, prefix); if (length) (*env)->SetByteArrayRegion(env, result, 4, (jsize)length, (jbyte *)contents); }
body_cleanup:
    free(contents); if (chunk) (*env)->DeleteLocalRef(env, chunk); if (input_type) (*env)->DeleteLocalRef(env, input_type); if (input) (*env)->DeleteLocalRef(env, input);
disconnect:
    if (connection) { jmethodID disconnect = (*env)->GetMethodID(env, http_type, "disconnect", "()V"); if (disconnect) (*env)->CallVoidMethod(env, connection, disconnect); (*env)->DeleteLocalRef(env, connection); }
cleanup:
    if (transformed_body) (*env)->DeleteLocalRef(env, transformed_body);
    if (url) (*env)->DeleteLocalRef(env, url); if (connection_type) (*env)->DeleteLocalRef(env, connection_type); if (http_type) (*env)->DeleteLocalRef(env, http_type); if (url_type) (*env)->DeleteLocalRef(env, url_type);
    return result;
}

static int write_exact_at(int fd, const unsigned char *buffer, size_t size, off_t offset);

static jbyteArray loader_download(JNIEnv *env, jobject receiver, jstring path_text,
                                  jobjectArray header_text, jint output_fd, jlong output_offset,
                                  jlong expected_length) {
    (void)receiver; jbyteArray result = NULL;
    if (runtime_guard() != 0 || output_fd < 0 || output_offset < 0 || expected_length < 0) {
        throw_class(env, "java/lang/SecurityException", "native loader download rejected"); return NULL;
    }
    const char *path = path_text == NULL ? NULL : (*env)->GetStringUTFChars(env, path_text, NULL);
    if (path == NULL || strncmp(path, "/v1/", 4) != 0 || strchr(path, '?') != NULL || strchr(path, '#') != NULL) {
        if (path) (*env)->ReleaseStringUTFChars(env, path_text, path);
        throw_class(env, "java/lang/SecurityException", "native loader rejected download path"); return NULL;
    }
    char url_buffer[1024]; int url_length = snprintf(url_buffer, sizeof(url_buffer), "%s%s", LOADER_API_ORIGIN, path);
    (*env)->ReleaseStringUTFChars(env, path_text, path);
    if (url_length <= 0 || (size_t)url_length >= sizeof(url_buffer)) { throw_class(env, "java/lang/IllegalArgumentException", "native loader path too long"); return NULL; }
    jstring url_text = (*env)->NewStringUTF(env, url_buffer);
    jstring method_text = (*env)->NewStringUTF(env, "GET");
    jclass url_type = (*env)->FindClass(env, "java/net/URL");
    jclass http_type = (*env)->FindClass(env, "java/net/HttpURLConnection");
    jclass connection_type = (*env)->FindClass(env, "java/net/URLConnection");
    jmethodID constructor = url_type == NULL ? NULL : (*env)->GetMethodID(env, url_type, "<init>", "(Ljava/lang/String;)V");
    jobject url = constructor == NULL ? NULL : (*env)->NewObject(env, url_type, constructor, url_text);
    jmethodID open = url_type == NULL ? NULL : (*env)->GetMethodID(env, url_type, "openConnection", "()Ljava/net/URLConnection;");
    jobject connection = (url == NULL || open == NULL) ? NULL : (*env)->CallObjectMethod(env, url, open);
    if (has_exception(env) || connection == NULL) { clear_exception(env); throw_io(env, "open HTTPS download"); goto cleanup_download; }
    jmethodID set_method = (*env)->GetMethodID(env, http_type, "setRequestMethod", "(Ljava/lang/String;)V");
    jmethodID redirects = (*env)->GetMethodID(env, http_type, "setInstanceFollowRedirects", "(Z)V");
    jmethodID response = (*env)->GetMethodID(env, http_type, "getResponseCode", "()I");
    jmethodID timeout = (*env)->GetMethodID(env, connection_type, "setConnectTimeout", "(I)V");
    jmethodID read_timeout = (*env)->GetMethodID(env, connection_type, "setReadTimeout", "(I)V");
    jmethodID property = (*env)->GetMethodID(env, connection_type, "setRequestProperty", "(Ljava/lang/String;Ljava/lang/String;)V");
    if (set_method == NULL || redirects == NULL || response == NULL || timeout == NULL || read_timeout == NULL || property == NULL) { clear_exception(env); throw_io(env, "resolve HTTPS download methods"); goto disconnect_download; }
    (*env)->CallVoidMethod(env, connection, timeout, 15000); (*env)->CallVoidMethod(env, connection, read_timeout, 30000);
    (*env)->CallVoidMethod(env, connection, redirects, JNI_FALSE); (*env)->CallVoidMethod(env, connection, set_method, method_text);
    jsize count = header_text == NULL ? 0 : (*env)->GetArrayLength(env, header_text);
    for (jsize i = 0; i < count; ++i) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, header_text, i); const char *text = item == NULL ? NULL : (*env)->GetStringUTFChars(env, item, NULL); const char *split = text == NULL ? NULL : strchr(text, ':');
        if (split == NULL || split == text) { if (text) (*env)->ReleaseStringUTFChars(env, item, text); if (item) (*env)->DeleteLocalRef(env, item); throw_class(env, "java/lang/IllegalArgumentException", "invalid download header"); goto disconnect_download; }
        size_t size = (size_t)(split - text); char name[128]; if (size >= sizeof(name)) { (*env)->ReleaseStringUTFChars(env, item, text); (*env)->DeleteLocalRef(env, item); throw_class(env, "java/lang/IllegalArgumentException", "download header too long"); goto disconnect_download; }
        memcpy(name, text, size); name[size] = '\0'; ++split; while (*split == ' ') ++split;
        jstring jname = (*env)->NewStringUTF(env, name); jstring jvalue = (*env)->NewStringUTF(env, split); (*env)->CallVoidMethod(env, connection, property, jname, jvalue);
        (*env)->ReleaseStringUTFChars(env, item, text); (*env)->DeleteLocalRef(env, item); (*env)->DeleteLocalRef(env, jname); (*env)->DeleteLocalRef(env, jvalue);
    }
    jint status = (*env)->CallIntMethod(env, connection, response);
    if (has_exception(env)) { clear_exception(env); throw_io(env, "read HTTPS download response"); goto disconnect_download; }
    if (!verify_pins(env, connection)) { throw_class(env, "java/lang/SecurityException", "HTTPS pin mismatch"); goto disconnect_download; }
    jmethodID content_length = (*env)->GetMethodID(env, connection_type, "getContentLengthLong", "()J");
    jlong actual_length = content_length == NULL ? -1 : (*env)->CallLongMethod(env, connection, content_length);
    if (status >= 200 && status < 300 && actual_length != expected_length) { throw_class(env, "java/io/IOException", "download length differs from signed size"); goto disconnect_download; }
    const char *input_name = status >= 400 ? "getErrorStream" : "getInputStream"; jmethodID input_method = (*env)->GetMethodID(env, connection_type, input_name, "()Ljava/io/InputStream;");
    jobject input = input_method == NULL ? NULL : (*env)->CallObjectMethod(env, connection, input_method); jclass input_type = (*env)->FindClass(env, "java/io/InputStream"); jmethodID read = input_type == NULL ? NULL : (*env)->GetMethodID(env, input_type, "read", "([B)I"); jbyteArray chunk = read == NULL ? NULL : (*env)->NewByteArray(env, 65536);
    unsigned char *error_body = NULL; size_t error_length = 0, error_capacity = 0; jlong received = 0;
    if (input != NULL && read != NULL && chunk != NULL) {
        for (;;) { jint bytes = (*env)->CallIntMethod(env, input, read, chunk); if (has_exception(env)) { clear_exception(env); throw_io(env, "read HTTPS download"); goto download_cleanup; } if (bytes < 0) break; if (bytes == 0) continue; jbyte block[65536]; (*env)->GetByteArrayRegion(env, chunk, 0, bytes, block); if (status >= 200 && status < 300) { if (received + bytes > expected_length || !write_exact_at(output_fd, (unsigned char *)block, (size_t)bytes, (off_t)(output_offset + received))) { throw_class(env, "java/io/IOException", "download output exceeds signed size"); goto download_cleanup; } received += bytes; } else if (!append_body(&error_body, &error_length, &error_capacity, (unsigned char *)block, (size_t)bytes)) { throw_class(env, "java/io/IOException", "download error body too large"); goto download_cleanup; } }
    }
    if (status >= 200 && status < 300 && received != expected_length) { throw_class(env, "java/io/EOFException", "download body truncated"); goto download_cleanup; }
    result = (*env)->NewByteArray(env, (jsize)(4 + error_length));
    if (result != NULL) { jbyte prefix[4] = {(jbyte)(status >> 24), (jbyte)(status >> 16), (jbyte)(status >> 8), (jbyte)status}; (*env)->SetByteArrayRegion(env, result, 0, 4, prefix); if (error_length) (*env)->SetByteArrayRegion(env, result, 4, (jsize)error_length, (jbyte *)error_body); }
download_cleanup:
    free(error_body); if (chunk) (*env)->DeleteLocalRef(env, chunk); if (input_type) (*env)->DeleteLocalRef(env, input_type); if (input) (*env)->DeleteLocalRef(env, input);
disconnect_download:
    if (connection) { jmethodID disconnect = (*env)->GetMethodID(env, http_type, "disconnect", "()V"); if (disconnect) (*env)->CallVoidMethod(env, connection, disconnect); (*env)->DeleteLocalRef(env, connection); }
cleanup_download:
    if (method_text) (*env)->DeleteLocalRef(env, method_text); if (url_text) (*env)->DeleteLocalRef(env, url_text); if (connection_type) (*env)->DeleteLocalRef(env, connection_type); if (http_type) (*env)->DeleteLocalRef(env, http_type); if (url_type) (*env)->DeleteLocalRef(env, url_type);
    return result;
}

static int read_exact_at(int fd, unsigned char *buffer, size_t size, off_t offset) {
    size_t done = 0;
    while (done < size) {
        ssize_t count = pread(fd, buffer + done, size - done, offset + (off_t)done);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return 0;
        done += (size_t)count;
    }
    return 1;
}

static int write_exact_at(int fd, const unsigned char *buffer, size_t size, off_t offset) {
    size_t done = 0;
    while (done < size) {
        ssize_t count = pwrite(fd, buffer + done, size - done, offset + (off_t)done);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return 0;
        done += (size_t)count;
    }
    return 1;
}

static jobject new_message_digest(JNIEnv *env, jclass digest_type) {
    jmethodID instance = (*env)->GetStaticMethodID(env, digest_type, "getInstance",
        "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    jstring name = (*env)->NewStringUTF(env, "SHA-256");
    jobject digest = (instance == NULL || name == NULL) ? NULL :
        (*env)->CallStaticObjectMethod(env, digest_type, instance, name);
    if (name != NULL) (*env)->DeleteLocalRef(env, name);
    return digest;
}

static int digest_update(JNIEnv *env, jclass digest_type, jobject digest, jbyteArray bytes) {
    jmethodID update = (*env)->GetMethodID(env, digest_type, "update", "([B)V");
    if (update == NULL) return 0;
    (*env)->CallVoidMethod(env, digest, update, bytes);
    return !has_exception(env);
}

static jbyteArray digest_finish(JNIEnv *env, jclass digest_type, jobject digest) {
    jmethodID finish = (*env)->GetMethodID(env, digest_type, "digest", "()[B");
    if (finish == NULL) return NULL;
    return (jbyteArray)(*env)->CallObjectMethod(env, digest, finish);
}

static int digest_matches(JNIEnv *env, jbyteArray digest, jstring expected) {
    if (digest == NULL || expected == NULL || (*env)->GetArrayLength(env, digest) != 32) return 0;
    const char *wanted = (*env)->GetStringUTFChars(env, expected, NULL);
    if (wanted == NULL) return 0;
    jbyte actual[32];
    (*env)->GetByteArrayRegion(env, digest, 0, 32, actual);
    char hex[65] = {0};
    for (int i = 0; i < 32; ++i) snprintf(hex + i * 2, 3, "%02x", (unsigned char)actual[i]);
    int matches = strcmp(hex, wanted) == 0;
    (*env)->ReleaseStringUTFChars(env, expected, wanted);
    return matches;
}

static void loader_decrypt_payload(
    JNIEnv *env, jobject receiver, jint input_fd, jlong ciphertext_offset, jint output_fd,
    jlong apk_size, jint chunk_count, jint chunk_size, jbyteArray nonce_prefix,
    jstring payload_id, jstring apk_sha256, jstring cipher_sha256, jbyteArray key) {
    (void)receiver;
    if (input_fd < 0 || output_fd < 0 || ciphertext_offset < 0 || apk_size <= 0 || apk_size > 128LL * 1024 * 1024 ||
        chunk_count <= 0 || chunk_size != 1024 * 1024 ||
        nonce_prefix == NULL || (*env)->GetArrayLength(env, nonce_prefix) != 8 ||
        key == NULL || (*env)->GetArrayLength(env, key) != 32) {
        throw_class(env, "java/lang/IllegalArgumentException", "invalid native payload parameters");
        return;
    }
    long long expected_chunks = (apk_size + chunk_size - 1) / chunk_size;
    if (expected_chunks != chunk_count) {
        throw_class(env, "java/lang/IllegalArgumentException", "payload chunk geometry mismatch");
        return;
    }
    struct stat input_info;
    long long cipher_size = apk_size + (long long)chunk_count * 16;
    if (fstat(input_fd, &input_info) != 0 || input_info.st_size < ciphertext_offset ||
        input_info.st_size - ciphertext_offset != cipher_size) {
        throw_class(env, "java/io/IOException", "payload ciphertext length mismatch");
        return;
    }
    jclass digest_type = (*env)->FindClass(env, "java/security/MessageDigest");
    jclass cipher_type = (*env)->FindClass(env, "javax/crypto/Cipher");
    jclass key_type = (*env)->FindClass(env, "javax/crypto/spec/SecretKeySpec");
    jclass gcm_type = (*env)->FindClass(env, "javax/crypto/spec/GCMParameterSpec");
    if (digest_type == NULL || cipher_type == NULL || key_type == NULL || gcm_type == NULL) return;
    jobject cipher_digest = new_message_digest(env, digest_type);
    jobject apk_digest = new_message_digest(env, digest_type);
    jmethodID cipher_instance = (*env)->GetStaticMethodID(env, cipher_type, "getInstance",
        "(Ljava/lang/String;)Ljavax/crypto/Cipher;");
    jstring algorithm = (*env)->NewStringUTF(env, "AES/GCM/NoPadding");
    jobject cipher = (cipher_instance == NULL || algorithm == NULL) ? NULL :
        (*env)->CallStaticObjectMethod(env, cipher_type, cipher_instance, algorithm);
    jmethodID key_ctor = (*env)->GetMethodID(env, key_type, "<init>",
        "([BLjava/lang/String;)V");
    jstring aes = (*env)->NewStringUTF(env, "AES");
    jobject secret = (key_ctor == NULL || aes == NULL) ? NULL :
        (*env)->NewObject(env, key_type, key_ctor, key, aes);
    jmethodID gcm_ctor = (*env)->GetMethodID(env, gcm_type, "<init>", "(I[B)V");
    jmethodID init = (*env)->GetMethodID(env, cipher_type, "init", "(ILjava/security/Key;Ljava/security/spec/AlgorithmParameterSpec;)V");
    jmethodID update_aad = (*env)->GetMethodID(env, cipher_type, "updateAAD", "([B)V");
    jmethodID do_final = (*env)->GetMethodID(env, cipher_type, "doFinal", "([B)[B");
    const char *payload = payload_id == NULL ? NULL : (*env)->GetStringUTFChars(env, payload_id, NULL);
    const char *apk_hash = apk_sha256 == NULL ? NULL : (*env)->GetStringUTFChars(env, apk_sha256, NULL);
    unsigned char *prefix = NULL;
    unsigned char *encrypted_bytes = NULL;
    jbyteArray aad = NULL;
    jbyteArray encrypted = NULL;
    jbyteArray nonce = NULL;
    jbyteArray plaintext = NULL;
    if (cipher_digest == NULL || apk_digest == NULL || cipher == NULL || secret == NULL ||
        gcm_ctor == NULL || init == NULL || update_aad == NULL || do_final == NULL ||
        payload == NULL || apk_hash == NULL || has_exception(env)) {
        clear_exception(env);
        if (payload != NULL) (*env)->ReleaseStringUTFChars(env, payload_id, payload);
        if (apk_hash != NULL) (*env)->ReleaseStringUTFChars(env, apk_sha256, apk_hash);
        throw_class(env, "java/io/IOException", "native payload crypto initialization failed");
        goto cleanup_crypto;
    }
    size_t prefix_size = strlen("WEKIT-APK-V1\n") + strlen(payload) + 1 + strlen(apk_hash) + 1;
    prefix = malloc(prefix_size + 1);
    if (prefix == NULL) { throw_io(env, "allocate payload AAD"); goto cleanup_strings; }
    int prefix_written = snprintf((char *)prefix, prefix_size + 1, "WEKIT-APK-V1\n%s\n%s\n", payload, apk_hash);
    if (prefix_written < 0 || (size_t)prefix_written != prefix_size) { free(prefix); throw_class(env, "java/io/IOException", "payload AAD construction failed"); goto cleanup_strings; }
    aad = (*env)->NewByteArray(env, (jsize)(prefix_size + 16));
    nonce = (*env)->NewByteArray(env, 12);
    long long plaintext_total = 0;
    int ok = aad != NULL && nonce != NULL;
    for (int index = 0; ok && index < chunk_count; ++index) {
        long long remaining = apk_size - plaintext_total;
        int block = (int)(remaining < chunk_size ? remaining : chunk_size);
        if (block <= 0) { ok = 0; break; }
        size_t encrypted_size = (size_t)block + 16;
        unsigned char *grown = realloc(encrypted_bytes, encrypted_size);
        if (grown != NULL) encrypted_bytes = grown;
        if (encrypted_bytes == NULL || !read_exact_at(input_fd, encrypted_bytes, encrypted_size,
            (off_t)ciphertext_offset + (off_t)(plaintext_total + (long long)index * 16))) { ok = 0; break; }
        encrypted = (*env)->NewByteArray(env, (jsize)encrypted_size);
        if (encrypted == NULL) { ok = 0; break; }
        (*env)->SetByteArrayRegion(env, encrypted, 0, (jsize)encrypted_size, (jbyte *)encrypted_bytes);
        if (!digest_update(env, digest_type, cipher_digest, encrypted)) { ok = 0; break; }
        jbyte nonce_bytes[12];
        (*env)->GetByteArrayRegion(env, nonce_prefix, 0, 8, nonce_bytes);
        nonce_bytes[8] = (jbyte)(index >> 24); nonce_bytes[9] = (jbyte)(index >> 16);
        nonce_bytes[10] = (jbyte)(index >> 8); nonce_bytes[11] = (jbyte)index;
        (*env)->SetByteArrayRegion(env, nonce, 0, 12, nonce_bytes);
        jobject params = (*env)->NewObject(env, gcm_type, gcm_ctor, 128, nonce);
        unsigned char aad_bytes[16];
        unsigned long long size_value = (unsigned long long)apk_size;
        for (int i = 0; i < 8; ++i) aad_bytes[i] = (unsigned char)(size_value >> (56 - 8 * i));
        aad_bytes[8] = (unsigned char)(chunk_count >> 24); aad_bytes[9] = (unsigned char)(chunk_count >> 16);
        aad_bytes[10] = (unsigned char)(chunk_count >> 8); aad_bytes[11] = (unsigned char)chunk_count;
        aad_bytes[12] = (unsigned char)(index >> 24); aad_bytes[13] = (unsigned char)(index >> 16);
        aad_bytes[14] = (unsigned char)(index >> 8); aad_bytes[15] = (unsigned char)index;
        (*env)->SetByteArrayRegion(env, aad, 0, (jsize)prefix_size, (jbyte *)prefix);
        (*env)->SetByteArrayRegion(env, aad, (jsize)prefix_size, 16, (jbyte *)aad_bytes);
        (*env)->CallVoidMethod(env, cipher, init, 2, secret, params);
        (*env)->CallVoidMethod(env, cipher, update_aad, aad);
        plaintext = (jbyteArray)(*env)->CallObjectMethod(env, cipher, do_final, encrypted);
        if (has_exception(env) || plaintext == NULL || (*env)->GetArrayLength(env, plaintext) != block) { clear_exception(env); ok = 0; break; }
        jsize plaintext_size = (*env)->GetArrayLength(env, plaintext);
        if (!digest_update(env, digest_type, apk_digest, plaintext)) { ok = 0; break; }
        jbyte *plain_bytes = (*env)->GetByteArrayElements(env, plaintext, NULL);
        if (plain_bytes == NULL || !write_exact_at(output_fd, (unsigned char *)plain_bytes, (size_t)plaintext_size, (off_t)plaintext_total)) { if (plain_bytes) (*env)->ReleaseByteArrayElements(env, plaintext, plain_bytes, JNI_ABORT); ok = 0; break; }
        (*env)->ReleaseByteArrayElements(env, plaintext, plain_bytes, JNI_ABORT);
        plaintext_total += block;
        (*env)->DeleteLocalRef(env, params); (*env)->DeleteLocalRef(env, encrypted); encrypted = NULL;
        (*env)->DeleteLocalRef(env, plaintext); plaintext = NULL;
    }
    struct stat output_info;
    if (ok && plaintext_total == apk_size &&
        (fstat(output_fd, &output_info) != 0 || output_info.st_size != apk_size)) ok = 0;
    if (ok && plaintext_total == apk_size) {
        unsigned char trailing;
        ssize_t trailing_size;
        do {
            trailing_size = pread(input_fd, &trailing, 1,
            (off_t)ciphertext_offset + (off_t)(apk_size + (long long)chunk_count * 16));
        } while (trailing_size < 0 && errno == EINTR);
        if (trailing_size > 0 || (trailing_size < 0 && errno != EINTR)) ok = 0;
        if (ok && cipher_sha256 != NULL) {
            jbyteArray actual = digest_finish(env, digest_type, cipher_digest);
            jbyteArray plain_actual = digest_finish(env, digest_type, apk_digest);
            ok = digest_matches(env, actual, cipher_sha256) && digest_matches(env, plain_actual, apk_sha256);
            if (actual) (*env)->DeleteLocalRef(env, actual); if (plain_actual) (*env)->DeleteLocalRef(env, plain_actual);
        } else ok = 0;
    } else ok = 0;
    if (!ok) throw_class(env, "java/io/IOException", "payload authentication or integrity check failed");
    free(encrypted_bytes); if (prefix) free(prefix); if (aad) (*env)->DeleteLocalRef(env, aad); if (nonce) (*env)->DeleteLocalRef(env, nonce);
cleanup_strings:
    if (payload != NULL) (*env)->ReleaseStringUTFChars(env, payload_id, payload);
    if (apk_hash != NULL) (*env)->ReleaseStringUTFChars(env, apk_sha256, apk_hash);
cleanup_crypto:
    if (plaintext) (*env)->DeleteLocalRef(env, plaintext); if (encrypted) (*env)->DeleteLocalRef(env, encrypted);
    if (algorithm) (*env)->DeleteLocalRef(env, algorithm); if (aes) (*env)->DeleteLocalRef(env, aes);
    if (secret) (*env)->DeleteLocalRef(env, secret); if (cipher) (*env)->DeleteLocalRef(env, cipher);
    if (cipher_digest) (*env)->DeleteLocalRef(env, cipher_digest); if (apk_digest) (*env)->DeleteLocalRef(env, apk_digest);
    if (digest_type) (*env)->DeleteLocalRef(env, digest_type); if (cipher_type) (*env)->DeleteLocalRef(env, cipher_type);
    if (key_type) (*env)->DeleteLocalRef(env, key_type); if (gcm_type) (*env)->DeleteLocalRef(env, gcm_type);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved; JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK || env == NULL) return JNI_ERR;
    jclass type = (*env)->FindClass(env, "dev/ujhhgtg/wekit/bootstrap/payload/LoaderNatives"); if (type == NULL) return JNI_ERR;
    static const JNINativeMethod methods[] = {
        {"create", "()I", (void *)loader_create}, {"seal", "(I)V", (void *)loader_seal},
        {"configuration", "()[Ljava/lang/String;", (void *)loader_configuration},
        {"verifyBootstrap", "(Landroid/content/Context;Ljava/lang/String;)Z", (void *)loader_verify_bootstrap},
        {"request", "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;[B)[B", (void *)loader_request},
        {"download", "(Ljava/lang/String;[Ljava/lang/String;IJJ)[B", (void *)loader_download},
        {"decryptPayload", "(IJIJII[BLjava/lang/String;Ljava/lang/String;Ljava/lang/String;[B)V", (void *)loader_decrypt_payload},
    };
    jint status = (*env)->RegisterNatives(env, type, methods, sizeof(methods) / sizeof(methods[0])); (*env)->DeleteLocalRef(env, type);
    return status == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}
