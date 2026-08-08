package main

/*
#include <stdlib.h>
#include <string.h>

typedef void (*wekit_callback)(void *user, int status, const char *url, const char *error);

static void wekit_invoke_callback(
    wekit_callback callback,
    void *user,
    int status,
    const char *url,
    const char *error
) {
    if (callback != NULL) {
        callback(user, status, url, error);
    }
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

//export wekit_tunnel_start_quick
func wekit_tunnel_start_quick(origin *C.char, callback C.wekit_callback, user unsafe.Pointer) unsafe.Pointer {
	if origin == nil {
		return nil
	}
	handle := startQuickTunnel(C.GoString(origin), cCallback(callback, user), requestQuickTunnel, runUpstreamTunnel)
	return registerHandle(handle)
}

//export wekit_tunnel_start_token
func wekit_tunnel_start_token(token *C.char, origin *C.char, callback C.wekit_callback, user unsafe.Pointer) unsafe.Pointer {
	if token == nil || origin == nil {
		return nil
	}
	// This milestone deliberately never copies or stores token material. The
	// symbol exists so later Android integration can compile against the stable
	// ABI, but its callback and status report explicit unsupported state.
	handle := startUnsupportedTokenTunnel("", C.GoString(origin), cCallback(callback, user))
	return registerHandle(handle)
}

//export wekit_tunnel_begin_login
func wekit_tunnel_begin_login(pointer unsafe.Pointer, callback C.wekit_callback, user unsafe.Pointer) C.int {
	handle := lookupHandle(pointer)
	if handle == nil {
		return C.int(resultInvalid)
	}
	return C.int(handle.beginLogin(cCallback(callback, user)))
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
	handle := unregisterHandle(pointer)
	if handle == nil {
		return C.int(resultInvalid)
	}
	result := handle.stop()
	C.free(pointer)
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

func registerHandle(handle *tunnelHandle) unsafe.Pointer {
	pointer := C.malloc(1)
	if pointer == nil {
		handle.stop()
		return nil
	}
	handleRegistry.Lock()
	handleRegistry.handles[pointer] = handle
	handleRegistry.Unlock()
	return pointer
}

func lookupHandle(pointer unsafe.Pointer) *tunnelHandle {
	if pointer == nil {
		return nil
	}
	handleRegistry.Lock()
	defer handleRegistry.Unlock()
	return handleRegistry.handles[pointer]
}

func unregisterHandle(pointer unsafe.Pointer) *tunnelHandle {
	if pointer == nil {
		return nil
	}
	handleRegistry.Lock()
	defer handleRegistry.Unlock()
	handle := handleRegistry.handles[pointer]
	delete(handleRegistry.handles, pointer)
	return handle
}

func cCallback(callback C.wekit_callback, user unsafe.Pointer) bridgeCallback {
	if callback == nil {
		return nil
	}
	return func(event bridgeEvent) {
		url := C.CString(boundText(event.URL, maxURLBytes))
		failure := C.CString(boundText(event.Error, maxErrorBytes))
		defer C.free(unsafe.Pointer(url))
		defer C.free(unsafe.Pointer(failure))
		C.wekit_invoke_callback(callback, user, C.int(event.Status), url, failure)
	}
}

func main() {}
