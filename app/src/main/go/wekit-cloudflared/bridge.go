package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/url"
	"regexp"
	"strings"
	"sync"

	"github.com/cloudflare/cloudflared/connection"
	"github.com/google/uuid"
)

const (
	maxURLBytes   = 2048
	maxErrorBytes = 512
	maxTokenBytes = 16 * 1024
)

var (
	tokenAccountPattern  = regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`)
	tokenEndpointPattern = regexp.MustCompile(`^[A-Za-z0-9.-]{1,255}$`)
)

const (
	resultOK          = 0
	resultInvalid     = -1
	resultUnsupported = -2
	resultBufferSmall = -3
)

type bridgeStatus int

const (
	statusStopped bridgeStatus = iota
	statusStarting
	statusConnected
	statusReconnecting
	statusFailed
	statusStopping
	statusUnsupported
)

type bridgeEvent struct {
	Status bridgeStatus
	URL    string
	Error  string
}

type bridgeSnapshot = bridgeEvent

type quickTunnel struct {
	URL         string
	Credentials connection.Credentials
}

type quickTunnelRequester func(context.Context) (quickTunnel, error)

type tunnelEventObserver interface {
	connected(url string)
	reconnecting()
	disconnected()
}

type tunnelRunner func(context.Context, string, quickTunnel, tunnelEventObserver) error

type bridgeCallback func(bridgeEvent)

type tunnelHandle struct {
	ctx      context.Context
	cancel   context.CancelFunc
	wg       sync.WaitGroup
	stopOnce sync.Once
	finalize sync.Once

	mu        sync.Mutex
	state     bridgeSnapshot
	callbacks *callbackDispatcher
}

func newTunnelHandle(callback bridgeCallback) *tunnelHandle {
	ctx, cancel := context.WithCancel(context.Background())
	return &tunnelHandle{
		ctx:       ctx,
		cancel:    cancel,
		state:     bridgeSnapshot{Status: statusStopped},
		callbacks: newCallbackDispatcher(callback),
	}
}

func startQuickTunnel(
	origin string,
	callback bridgeCallback,
	request quickTunnelRequester,
	run tunnelRunner,
) *tunnelHandle {
	handle := newTunnelHandle(callback)
	handle.wg.Add(1)
	go func() {
		defer handle.wg.Done()
		handle.publish(bridgeEvent{Status: statusStarting})

		if err := validateLoopbackOrigin(origin); err != nil {
			handle.fail(err.Error(), nil)
			return
		}
		if request == nil || run == nil {
			handle.fail("quick tunnel runtime is unavailable", nil)
			return
		}

		quick, err := request(handle.ctx)
		if err != nil {
			if errors.Is(err, context.Canceled) || handle.ctx.Err() != nil {
				handle.publishStopped()
				return
			}
			handle.fail("quick tunnel request failed: "+err.Error(), nil)
			return
		}
		quick.URL = boundText(quick.URL, maxURLBytes)
		observer := handleObserver{handle: handle, url: quick.URL}
		if err := run(handle.ctx, origin, quick, observer); err != nil && !errors.Is(err, context.Canceled) {
			handle.fail("tunnel transport failed: "+err.Error(), credentialStrings(quick.Credentials))
			return
		}
		handle.publishStopped()
	}()
	return handle
}

func startTokenTunnel(token, origin string, callback bridgeCallback, run tunnelRunner) *tunnelHandle {
	handle := newTunnelHandle(callback)
	handle.wg.Add(1)
	go func() {
		defer handle.wg.Done()
		handle.publish(bridgeEvent{Status: statusStarting})

		if err := validateLoopbackOrigin(origin); err != nil {
			handle.fail(err.Error(), []string{token})
			return
		}
		credentials, err := parseTunnelToken(token)
		if err != nil {
			handle.fail("tunnel token is invalid", []string{token})
			return
		}
		if run == nil {
			handle.fail("authenticated tunnel runtime is unavailable", append(credentialStrings(credentials), token))
			return
		}

		tunnel := quickTunnel{Credentials: credentials}
		observer := handleObserver{handle: handle}
		if err := run(handle.ctx, origin, tunnel, observer); err != nil && !errors.Is(err, context.Canceled) {
			secrets := append(credentialStrings(credentials), token)
			handle.fail("tunnel transport failed: "+err.Error(), secrets)
			return
		}
		handle.publishStopped()
	}()
	return handle
}

func parseTunnelToken(raw string) (connection.Credentials, error) {
	if len(raw) == 0 || len(raw) > maxTokenBytes || strings.TrimSpace(raw) != raw {
		return connection.Credentials{}, errors.New("tunnel token is invalid")
	}
	payload, err := base64.StdEncoding.Strict().DecodeString(raw)
	if err != nil || len(payload) == 0 || len(payload) > maxTokenBytes {
		return connection.Credentials{}, errors.New("tunnel token is invalid")
	}
	var token connection.TunnelToken
	decoder := json.NewDecoder(bytes.NewReader(payload))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&token); err != nil {
		return connection.Credentials{}, errors.New("tunnel token is invalid")
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return connection.Credentials{}, errors.New("tunnel token is invalid")
	}
	if !tokenAccountPattern.MatchString(token.AccountTag) ||
		len(token.TunnelSecret) != 32 || token.TunnelID == uuid.Nil ||
		(token.Endpoint != "" && !tokenEndpointPattern.MatchString(token.Endpoint)) {
		return connection.Credentials{}, errors.New("tunnel token is invalid")
	}
	return token.Credentials(), nil
}

func (h *tunnelHandle) beginLogin(callback bridgeCallback) int {
	event := bridgeEvent{
		Status: statusUnsupported,
		Error:  "browser login is not implemented in this Quick Tunnel proof of concept",
	}
	if callback != nil {
		h.callbacks.enqueueWith(callback, event)
	}
	return resultUnsupported
}

func (h *tunnelHandle) selectExisting(_, _ string) int {
	return resultUnsupported
}

func (h *tunnelHandle) requestStop() {
	h.stopOnce.Do(func() {
		h.publish(bridgeEvent{Status: statusStopping})
		h.cancel()
		h.wg.Wait()
		h.publishStopped()
	})
}

func (h *tunnelHandle) stop() int {
	h.requestStop()
	h.callbacks.close(false)
	<-h.callbacks.done
	return resultOK
}

func (h *tunnelHandle) stopFromCallback() int {
	h.requestStop()
	h.callbacks.close(true)
	return resultOK
}

func (h *tunnelHandle) callbacksDone() <-chan struct{} {
	return h.callbacks.done
}

func (h *tunnelHandle) wait() {
	h.wg.Wait()
}

func (h *tunnelHandle) snapshotValue() bridgeSnapshot {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.state
}

func (h *tunnelHandle) snapshot() bridgeSnapshot {
	return h.snapshotValue()
}

func (h *tunnelHandle) publish(event bridgeEvent) {
	h.publishTransition(event, false)
}

func (h *tunnelHandle) publishObserverTransition(event bridgeEvent) bool {
	return h.publishTransition(event, true)
}

func (h *tunnelHandle) publishTransition(event bridgeEvent, rejectAfterStop bool) bool {
	event.URL = boundText(event.URL, maxURLBytes)
	event.Error = boundText(event.Error, maxErrorBytes)

	h.mu.Lock()
	defer h.mu.Unlock()
	if rejectAfterStop &&
		(h.state.Status == statusStopping || h.state.Status == statusStopped || h.ctx.Err() != nil) {
		return false
	}
	h.state = event
	h.callbacks.enqueue(event)
	return true
}

func (h *tunnelHandle) publishStopped() {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.state.Status == statusStopped {
		return
	}
	event := bridgeEvent{Status: statusStopped}
	h.state = event
	h.callbacks.enqueue(event)
}

func (h *tunnelHandle) fail(message string, secrets []string) {
	h.publish(bridgeEvent{Status: statusFailed, Error: sanitizeError(message, secrets)})
}

type handleObserver struct {
	handle *tunnelHandle
	url    string
}

func (o handleObserver) connected(url string) {
	if url == "" {
		url = o.url
	}
	o.handle.publishObserverTransition(bridgeEvent{Status: statusConnected, URL: url})
}

func (o handleObserver) reconnecting() {
	o.handle.publishObserverTransition(bridgeEvent{Status: statusReconnecting})
}

func (o handleObserver) disconnected() {
	if o.handle.ctx.Err() != nil {
		o.handle.publishStopped()
	} else {
		o.handle.publishObserverTransition(bridgeEvent{Status: statusReconnecting})
	}
}

func validateLoopbackOrigin(origin string) error {
	parsed, err := url.ParseRequestURI(origin)
	if err != nil {
		return errors.New("origin must be an absolute HTTP loopback URL")
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return errors.New("origin must use HTTP or HTTPS")
	}
	if parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" {
		return errors.New("origin must not contain credentials, query, or fragment")
	}
	if parsed.Path != "" && parsed.Path != "/" {
		return errors.New("origin must not contain a path")
	}
	host := parsed.Hostname()
	ip := net.ParseIP(host)
	if !strings.EqualFold(host, "localhost") && (ip == nil || !ip.IsLoopback()) {
		return errors.New("origin host must be loopback")
	}
	if parsed.Port() == "" {
		return errors.New("origin must include a port")
	}
	return nil
}

func credentialStrings(credentials connection.Credentials) []string {
	return []string{
		credentials.AccountTag,
		string(credentials.TunnelSecret),
		base64.StdEncoding.EncodeToString(credentials.TunnelSecret),
	}
}

func sanitizeError(message string, secrets []string) string {
	for _, secret := range secrets {
		if secret != "" {
			message = strings.ReplaceAll(message, secret, "[redacted]")
		}
	}
	return boundText(message, maxErrorBytes)
}

func boundText(value string, maxBytes int) string {
	if len(value) <= maxBytes {
		return value
	}
	return strings.ToValidUTF8(value[:maxBytes], "")
}

func marshalSnapshot(snapshot bridgeSnapshot) ([]byte, error) {
	return json.Marshal(struct {
		Status string `json:"status"`
		URL    string `json:"url"`
		Error  string `json:"error"`
	}{
		Status: statusName(snapshot.Status),
		URL:    boundText(snapshot.URL, maxURLBytes),
		Error:  boundText(snapshot.Error, maxErrorBytes),
	})
}

func statusName(status bridgeStatus) string {
	switch status {
	case statusStopped:
		return "STOPPED"
	case statusStarting:
		return "STARTING"
	case statusConnected:
		return "CONNECTED"
	case statusReconnecting:
		return "RECONNECTING"
	case statusFailed:
		return "FAILED"
	case statusStopping:
		return "STOPPING"
	case statusUnsupported:
		return "UNSUPPORTED"
	default:
		return "UNKNOWN"
	}
}
