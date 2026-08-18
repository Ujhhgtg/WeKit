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
typedef int jsize;
typedef unsigned short jchar;
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

static char *wekit_copy_jstring_bounded(JNIEnv *env, jstring value, size_t max_len) {
#ifdef __ANDROID__
	if (value == NULL) {
		return NULL;
	}
	const char *characters = (*env)->GetStringUTFChars(env, value, NULL);
	if (characters == NULL) {
		return NULL;
	}
	size_t length = strnlen(characters, max_len + 1);
	if (length > max_len) {
		(*env)->ReleaseStringUTFChars(env, value, characters);
		return NULL;
	}
	char *copy = malloc(length + 1);
	if (copy != NULL) {
		memcpy(copy, characters, length);
		copy[length] = '\0';
	}
	(*env)->ReleaseStringUTFChars(env, value, characters);
	return copy;
#else
	return NULL;
#endif
}

static void wekit_free_secret(char *value) {
	if (value == NULL) {
		return;
	}
	volatile unsigned char *cursor = (volatile unsigned char *)value;
	while (*cursor != 0) {
		*cursor++ = 0;
	}
	free(value);
}

static jstring wekit_new_jstring(JNIEnv *env, const char *value) {
#ifdef __ANDROID__
	return (*env)->NewStringUTF(env, value);
#else
	return NULL;
#endif
}

static jstring wekit_new_jstring_utf16(JNIEnv *env, const jchar *value, jsize length) {
#ifdef __ANDROID__
	if (value == NULL || length < 0) {
		return NULL;
	}
	return (*env)->NewString(env, value, length);
#else
	return NULL;
#endif
}
*/
import "C"

import (
	"context"
	"errors"
	"sync"
	"unicode/utf16"
	"unicode/utf8"
	"unsafe"
)

var handleRegistry = struct {
	sync.Mutex
	handles map[unsafe.Pointer]*tunnelHandle
}{handles: make(map[unsafe.Pointer]*tunnelHandle)}

var authHandleRegistry = struct {
	sync.Mutex
	nextID  uint64
	handles map[uint64]*independentAuthHandle
}{handles: make(map[uint64]*independentAuthHandle)}

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
	client := newAuthHTTPClient()
	transfer, err := newLoginTransfer(client, nil)
	if err != nil {
		client.CloseIdleConnections()
		return C.int(resultInvalid)
	}
	return C.int(handle.beginLogin(
		cCallback(callback, user, readyCallbackIdentity(pointer)),
		transfer,
		defaultAuthAPIFactory(client),
		client.CloseIdleConnections,
	))
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
	return C.int(handle.selectExisting(C.GoString(tunnelID), C.GoString(hostname)))
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
	rawOrigin := C.wekit_copy_jstring_bounded(env, origin, C.size_t(maxURLBytes))
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
	rawToken := C.wekit_copy_jstring_bounded(env, token, C.size_t(maxTokenBytes))
	if rawToken == nil {
		return 0
	}
	defer C.wekit_free_secret(rawToken)
	rawOrigin := C.wekit_copy_jstring_bounded(env, origin, C.size_t(maxURLBytes))
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

//export Java_dev_ujhhgtg_wekit_features_items_chat_ReadReceiptsTunnelNative_nativeAuthBegin
func Java_dev_ujhhgtg_wekit_features_items_chat_ReadReceiptsTunnelNative_nativeAuthBegin(
	env *C.JNIEnv,
	receiver C.jobject,
) C.jlong {
	_ = env
	_ = receiver
	client := newAuthHTTPClient()
	transfer, err := newLoginTransfer(client, nil)
	if err != nil {
		client.CloseIdleConnections()
		return 0
	}
	handle := newIndependentAuthHandle(
		transfer,
		defaultAuthAPIFactory(client),
		client.CloseIdleConnections,
	)
	id := registerAuthHandle(handle)
	return C.jlong(id)
}

//export Java_dev_ujhhgtg_wekit_features_items_chat_ReadReceiptsTunnelNative_nativeAuthStatus
func Java_dev_ujhhgtg_wekit_features_items_chat_ReadReceiptsTunnelNative_nativeAuthStatus(
	env *C.JNIEnv,
	receiver C.jobject,
	pointer C.jlong,
) C.jstring {
	_ = receiver
	handle := lookupAuthHandle(authID(pointer))
	if handle == nil {
		return newJNIString(env, []byte(`{"generation":0,"authorizationUrl":"","state":"FAILED","accountId":"","error":"invalid browser login handle","selectedTunnelId":"","selectedHostname":""}`))
	}
	payload, err := handle.statusJSON()
	if err != nil {
		return newJNIString(env, []byte(`{"generation":0,"authorizationUrl":"","state":"FAILED","accountId":"","error":"could not encode browser login status","selectedTunnelId":"","selectedHostname":""}`))
	}
	return newJNIString(env, payload)
}

//export Java_dev_ujhhgtg_wekit_features_items_chat_ReadReceiptsTunnelNative_nativeAuthList
func Java_dev_ujhhgtg_wekit_features_items_chat_ReadReceiptsTunnelNative_nativeAuthList(
	env *C.JNIEnv,
	receiver C.jobject,
	pointer C.jlong,
) C.jstring {
	_ = receiver
	handle := lookupAuthHandle(authID(pointer))
	if handle == nil {
		return newJNIString(env, []byte(`{"generation":0,"tunnels":[],"error":"invalid browser login handle"}`))
	}
	payload, err := handle.listJSON(context.Background())
	if err != nil {
		failure, marshalErr := marshalBoundedAuthJSON(struct {
			Generation uint64           `json:"generation"`
			Tunnels    []existingTunnel `json:"tunnels"`
			Error      string           `json:"error"`
		}{Generation: handle.generation, Tunnels: []existingTunnel{}, Error: "could not list Cloudflare tunnels"})
		if marshalErr != nil {
			return nullJString()
		}
		return newJNIString(env, failure)
	}
	return newJNIString(env, payload)
}

//export Java_dev_ujhhgtg_wekit_features_items_chat_ReadReceiptsTunnelNative_nativeAuthSelect
func Java_dev_ujhhgtg_wekit_features_items_chat_ReadReceiptsTunnelNative_nativeAuthSelect(
	env *C.JNIEnv,
	receiver C.jobject,
	pointer C.jlong,
	tunnelID C.jstring,
	hostname C.jstring,
) C.jstring {
	_ = receiver
	handle := lookupAuthHandle(authID(pointer))
	if handle == nil {
		return nullJString()
	}
	rawTunnelID := C.wekit_copy_jstring_bounded(env, tunnelID, 64)
	if rawTunnelID == nil {
		return nullJString()
	}
	defer C.free(unsafe.Pointer(rawTunnelID))
	rawHostname := C.wekit_copy_jstring_bounded(env, hostname, C.size_t(maxURLBytes))
	if rawHostname == nil {
		return nullJString()
	}
	defer C.free(unsafe.Pointer(rawHostname))
	token, err := handle.selectToken(
		context.Background(),
		C.GoString(rawTunnelID),
		C.GoString(rawHostname),
	)
	if err != nil || len(token) == 0 || len(token) > maxTokenBytes {
		return nullJString()
	}
	cToken := C.CString(token)
	if cToken == nil {
		return nullJString()
	}
	defer C.wekit_free_secret(cToken)
	return C.wekit_new_jstring(env, cToken)
}

//export Java_dev_ujhhgtg_wekit_features_items_chat_ReadReceiptsTunnelNative_nativeAuthCancel
func Java_dev_ujhhgtg_wekit_features_items_chat_ReadReceiptsTunnelNative_nativeAuthCancel(
	env *C.JNIEnv,
	receiver C.jobject,
	pointer C.jlong,
) C.jint {
	_ = env
	_ = receiver
	if !closeRegisteredAuthHandle(authID(pointer)) {
		return C.jint(resultInvalid)
	}
	return C.jint(resultOK)
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

func registerAuthHandle(handle *independentAuthHandle) uint64 {
	if handle == nil {
		return 0
	}
	authHandleRegistry.Lock()
	if authHandleRegistry.nextID == uint64(^uint64(0)>>1) {
		authHandleRegistry.Unlock()
		handle.close()
		return 0
	}
	authHandleRegistry.nextID++
	id := authHandleRegistry.nextID
	authHandleRegistry.handles[id] = handle
	authHandleRegistry.Unlock()
	return id
}

func lookupAuthHandle(id uint64) *independentAuthHandle {
	if id == 0 {
		return nil
	}
	authHandleRegistry.Lock()
	defer authHandleRegistry.Unlock()
	return authHandleRegistry.handles[id]
}

func closeRegisteredAuthHandle(id uint64) bool {
	if id == 0 {
		return false
	}
	authHandleRegistry.Lock()
	handle := authHandleRegistry.handles[id]
	if handle != nil {
		delete(authHandleRegistry.handles, id)
	}
	authHandleRegistry.Unlock()
	if handle == nil {
		return false
	}
	handle.close()
	return true
}

func authID(value C.jlong) uint64 {
	if value <= 0 {
		return 0
	}
	return uint64(value)
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

func newJNIString(env *C.JNIEnv, payload []byte) C.jstring {
	encoded, err := encodeJNIUTF16(payload)
	if err != nil {
		return nullJString()
	}
	return C.wekit_new_jstring_utf16(
		env,
		(*C.jchar)(unsafe.Pointer(&encoded[0])),
		C.jsize(len(encoded)),
	)
}

func nullJString() (value C.jstring) {
	return
}

func encodeJNIUTF16(payload []byte) ([]uint16, error) {
	if len(payload) == 0 || len(payload) > maxAuthJSONBytes || !utf8.Valid(payload) {
		return nil, errors.New("JNI string payload is invalid")
	}
	encoded := utf16.Encode([]rune(string(payload)))
	if len(encoded) == 0 || len(encoded) > maxAuthJSONBytes {
		return nil, errors.New("JNI string payload exceeds supported bounds")
	}
	return encoded, nil
}

func main() {}
