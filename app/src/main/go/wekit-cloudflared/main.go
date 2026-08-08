package main

/*
#include <stdlib.h>
#include <string.h>

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
	// This milestone deliberately never copies or stores token material. The
	// symbol exists so later Android integration can compile against the stable
	// ABI, but its callback and status report explicit unsupported state.
	identity := newCallbackIdentity()
	handle := startUnsupportedTokenTunnel("", C.GoString(origin), cCallback(callback, user, identity))
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
