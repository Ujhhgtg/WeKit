package main

/*
#include <stdlib.h>
#include <string.h>
#ifdef __ANDROID__
#include <jni.h>
#else
typedef struct wekit_jni_env JNIEnv;
typedef void *jobject;
typedef void *jstring;
typedef long long jlong;
typedef int jint;
#endif

typedef void (*wekit_callback)(void *user, int status, const char *url, const char *error);

static _Thread_local void *wekit_active_callback_handle;

static void wekit_invoke_callback(
    wekit_callback callback,
    void *handle,
    void *user,
    int status,
    const char *url,
    const char *error
) {
    if (callback != NULL) {
		void *previous = wekit_active_callback_handle;
		wekit_active_callback_handle = handle;
        callback(user, status, url, error);
		wekit_active_callback_handle = previous;
    }
}

static int wekit_callback_is_for(void *handle) {
	return wekit_active_callback_handle == handle;
}

static char *wekit_copy_jstring(JNIEnv *env, jstring value) {
#ifdef __ANDROID__
	if (value == NULL) {
		return NULL;
	}
	const char *characters = (*env)->GetStringUTFChars(env, value, NULL);
	if (characters == NULL) {
		return NULL;
	}
	char *copy = strdup(characters);
	(*env)->ReleaseStringUTFChars(env, value, characters);
	return copy;
#else
	return NULL;
#endif
}

static jstring wekit_new_jstring(JNIEnv *env, const char *value) {
#ifdef __ANDROID__
	return (*env)->NewStringUTF(env, value);
#else
	return NULL;
#endif
}
*/
import "C"

import (
	"sync"
	"unsafe"
)

var handleRegistry = struct {
	sync.Mutex
	handles map[unsafe.Pointer]*tunnelHandle
}{handles: make(map[unsafe.Pointer]*tunnelHandle)}

type callbackIdentity struct {
	ready   chan struct{}
	pointer unsafe.Pointer
}

func newCallbackIdentity() *callbackIdentity {
	return &callbackIdentity{ready: make(chan struct{})}
}

func readyCallbackIdentity(pointer unsafe.Pointer) *callbackIdentity {
	identity := newCallbackIdentity()
	identity.set(pointer)
	return identity
}

func (i *callbackIdentity) set(pointer unsafe.Pointer) {
	i.pointer = pointer
	close(i.ready)
}

func (i *callbackIdentity) get() unsafe.Pointer {
	<-i.ready
	return i.pointer
}

//export wekit_tunnel_start_quick
func wekit_tunnel_start_quick(origin *C.char, callback C.wekit_callback, user unsafe.Pointer) unsafe.Pointer {
	if origin == nil {
		return nil
	}
	identity := newCallbackIdentity()
	handle := startQuickTunnel(C.GoString(origin), cCallback(callback, user, identity), requestQuickTunnel, runUpstreamTunnel)
	return registerHandle(handle, identity)
}

//export wekit_tunnel_start_token
func wekit_tunnel_start_token(token *C.char, origin *C.char, callback C.wekit_callback, user unsafe.Pointer) unsafe.Pointer {
	if token == nil || origin == nil {
		return nil
	}
	identity := newCallbackIdentity()
	handle := startTokenTunnel(
		C.GoString(token),
		C.GoString(origin),
		cCallback(callback, user, identity),
		runUpstreamTunnel,
	)
	return registerHandle(handle, identity)
}

//export wekit_tunnel_begin_login
func wekit_tunnel_begin_login(pointer unsafe.Pointer, callback C.wekit_callback, user unsafe.Pointer) C.int {
	handle := lookupHandle(pointer)
	if handle == nil {
		return C.int(resultInvalid)
	}
	return C.int(handle.beginLogin(cCallback(callback, user, readyCallbackIdentity(pointer))))
}

//export wekit_tunnel_select_existing
func wekit_tunnel_select_existing(pointer unsafe.Pointer, tunnelID *C.char, hostname *C.char) C.int {
	if tunnelID == nil || hostname == nil {
		return C.int(resultInvalid)
	}
	handle := lookupHandle(pointer)
	if handle == nil {
		return C.int(resultInvalid)
	}
	return C.int(handle.selectExisting("", ""))
}

//export wekit_tunnel_stop
func wekit_tunnel_stop(pointer unsafe.Pointer) C.int {
	handle := lookupHandle(pointer)
	if handle == nil {
		return C.int(resultInvalid)
	}
	reentrant := C.wekit_callback_is_for(pointer) != 0
	var result int
	if reentrant {
		result = handle.stopFromCallback()
		go finalizeHandle(pointer, handle)
	} else {
		result = handle.stop()
		finalizeHandle(pointer, handle)
	}
	return C.int(result)
}

//export wekit_tunnel_status
func wekit_tunnel_status(pointer unsafe.Pointer, buffer *C.char, bufferLen C.size_t) C.int {
	if buffer == nil || bufferLen == 0 {
		return C.int(resultInvalid)
	}
	handle := lookupHandle(pointer)
	if handle == nil {
		return C.int(resultInvalid)
	}
	payload, err := marshalSnapshot(handle.snapshot())
	if err != nil {
		return C.int(resultInvalid)
	}
	if uint64(bufferLen) <= uint64(len(payload)) {
		return C.int(resultBufferSmall)
	}
	if len(payload) > 0 {
		C.memcpy(unsafe.Pointer(buffer), unsafe.Pointer(&payload[0]), C.size_t(len(payload)))
	}
	C.memset(unsafe.Add(unsafe.Pointer(buffer), len(payload)), 0, 1)
	return C.int(resultOK)
}

//export Java_dev_ujhhgtg_wekit_features_items_chat_ReadReceiptsTunnelNative_nativeStartQuick
func Java_dev_ujhhgtg_wekit_features_items_chat_ReadReceiptsTunnelNative_nativeStartQuick(
	env *C.JNIEnv,
	receiver C.jobject,
	origin C.jstring,
) C.jlong {
	_ = receiver
	rawOrigin := C.wekit_copy_jstring(env, origin)
	if rawOrigin == nil {
		return 0
	}
	defer C.free(unsafe.Pointer(rawOrigin))
	identity := newCallbackIdentity()
	handle := startQuickTunnel(C.GoString(rawOrigin), nil, requestQuickTunnel, runUpstreamTunnel)
	return C.jlong(uintptr(registerHandle(handle, identity)))
}

//export Java_dev_ujhhgtg_wekit_features_items_chat_ReadReceiptsTunnelNative_nativeStartToken
func Java_dev_ujhhgtg_wekit_features_items_chat_ReadReceiptsTunnelNative_nativeStartToken(
	env *C.JNIEnv,
	receiver C.jobject,
	token C.jstring,
	origin C.jstring,
) C.jlong {
	_ = receiver
	rawToken := C.wekit_copy_jstring(env, token)
	if rawToken == nil {
		return 0
	}
	defer C.free(unsafe.Pointer(rawToken))
	rawOrigin := C.wekit_copy_jstring(env, origin)
	if rawOrigin == nil {
		return 0
	}
	defer C.free(unsafe.Pointer(rawOrigin))
	identity := newCallbackIdentity()
	handle := startTokenTunnel(C.GoString(rawToken), C.GoString(rawOrigin), nil, runUpstreamTunnel)
	return C.jlong(uintptr(registerHandle(handle, identity)))
}

//export Java_dev_ujhhgtg_wekit_features_items_chat_ReadReceiptsTunnelNative_nativeStop
func Java_dev_ujhhgtg_wekit_features_items_chat_ReadReceiptsTunnelNative_nativeStop(
	env *C.JNIEnv,
	receiver C.jobject,
	pointer C.jlong,
) C.jint {
	_ = env
	_ = receiver
	return wekit_tunnel_stop(unsafe.Pointer(uintptr(pointer)))
}

//export Java_dev_ujhhgtg_wekit_features_items_chat_ReadReceiptsTunnelNative_nativeStatus
func Java_dev_ujhhgtg_wekit_features_items_chat_ReadReceiptsTunnelNative_nativeStatus(
	env *C.JNIEnv,
	receiver C.jobject,
	pointer C.jlong,
) C.jstring {
	_ = receiver
	handle := lookupHandle(unsafe.Pointer(uintptr(pointer)))
	if handle == nil {
		payload := C.CString(`{"status":"FAILED","url":"","error":"invalid tunnel handle"}`)
		defer C.free(unsafe.Pointer(payload))
		return C.wekit_new_jstring(env, payload)
	}
	payload, err := marshalSnapshot(handle.snapshot())
	if err != nil {
		failure := C.CString(`{"status":"FAILED","url":"","error":"could not encode tunnel status"}`)
		defer C.free(unsafe.Pointer(failure))
		return C.wekit_new_jstring(env, failure)
	}
	cPayload := C.CString(string(payload))
	defer C.free(unsafe.Pointer(cPayload))
	return C.wekit_new_jstring(env, cPayload)
}

func registerHandle(handle *tunnelHandle, identity *callbackIdentity) unsafe.Pointer {
	pointer := C.malloc(1)
	if pointer == nil {
		identity.set(nil)
		handle.stop()
		return nil
	}
	handleRegistry.Lock()
	handleRegistry.handles[pointer] = handle
	handleRegistry.Unlock()
	// Publish the callback identity only after lookupHandle can resolve it. The
	// initial STARTING callback may immediately call wekit_tunnel_stop.
	identity.set(pointer)
	return pointer
}

func finalizeHandle(pointer unsafe.Pointer, handle *tunnelHandle) {
	handle.finalize.Do(func() {
		<-handle.callbacksDone()
		if unregisterHandle(pointer, handle) {
			C.free(pointer)
		}
	})
}

func lookupHandle(pointer unsafe.Pointer) *tunnelHandle {
	if pointer == nil {
		return nil
	}
	handleRegistry.Lock()
	defer handleRegistry.Unlock()
	return handleRegistry.handles[pointer]
}

func unregisterHandle(pointer unsafe.Pointer, expected *tunnelHandle) bool {
	if pointer == nil {
		return false
	}
	handleRegistry.Lock()
	defer handleRegistry.Unlock()
	handle := handleRegistry.handles[pointer]
	if handle != expected {
		return false
	}
	delete(handleRegistry.handles, pointer)
	return true
}

func cCallback(callback C.wekit_callback, user unsafe.Pointer, identity *callbackIdentity) bridgeCallback {
	if callback == nil {
		return nil
	}
	return func(event bridgeEvent) {
		url := C.CString(boundText(event.URL, maxURLBytes))
		failure := C.CString(boundText(event.Error, maxErrorBytes))
		defer C.free(unsafe.Pointer(url))
		defer C.free(unsafe.Pointer(failure))
		C.wekit_invoke_callback(callback, identity.get(), user, C.int(event.Status), url, failure)
	}
}

func main() {}
