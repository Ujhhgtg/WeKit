#ifndef WEKIT_CLOUDFLARED_BRIDGE_H
#define WEKIT_CLOUDFLARED_BRIDGE_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void *wekit_tunnel_handle;

typedef enum {
    WEKIT_TUNNEL_STOPPED = 0,
    WEKIT_TUNNEL_STARTING = 1,
    WEKIT_TUNNEL_CONNECTED = 2,
    WEKIT_TUNNEL_RECONNECTING = 3,
    WEKIT_TUNNEL_FAILED = 4,
    WEKIT_TUNNEL_STOPPING = 5,
    WEKIT_TUNNEL_UNSUPPORTED = 6,
} wekit_tunnel_status_code;

typedef void (*wekit_callback)(void *user, int status, const char *url, const char *error);

wekit_tunnel_handle wekit_tunnel_start_quick(const char *origin, wekit_callback callback, void *user);
wekit_tunnel_handle wekit_tunnel_start_token(const char *token, const char *origin, wekit_callback callback, void *user);
int wekit_tunnel_begin_login(wekit_tunnel_handle handle, wekit_callback callback, void *user);
int wekit_tunnel_select_existing(wekit_tunnel_handle handle, const char *tunnel_id, const char *hostname);
int wekit_tunnel_stop(wekit_tunnel_handle handle);
int wekit_tunnel_status(wekit_tunnel_handle handle, char *buffer, size_t buffer_len);

#ifdef __cplusplus
}
#endif

#endif
