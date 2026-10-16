#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

static void throw_io(JNIEnv *env, const char *operation) {
    const int error = errno;
    char message[256];
    snprintf(message, sizeof(message), "%s: errno=%d (%s)", operation, error, strerror(error));
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), message);
}

JNIEXPORT jint JNICALL
Java_dev_ujhhgtg_wekit_bootstrap_payload_MemoryApkNative_create(JNIEnv *env, jobject receiver) {
    (void) receiver;
    int fd = (int) syscall(SYS_memfd_create, "wekit-payload", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (fd == -1) throw_io(env, "memfd_create");
    return fd;
}

JNIEXPORT void JNICALL
Java_dev_ujhhgtg_wekit_bootstrap_payload_MemoryApkNative_seal(JNIEnv *env, jobject receiver, jint fd) {
    (void) receiver;
    const int seals = F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL;
    if (fcntl(fd, F_ADD_SEALS, seals) == -1) {
        throw_io(env, "F_ADD_SEALS");
        return;
    }
    const int actual = fcntl(fd, F_GET_SEALS);
    if (actual == -1) {
        throw_io(env, "F_GET_SEALS");
        return;
    }
    if ((actual & seals) != seals) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), "memfd seals missing");
    }
}
