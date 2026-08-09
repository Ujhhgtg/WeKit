package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/cloudflare/cloudflared/connection"
	"github.com/google/uuid"
)

type eventRecorder struct {
	mu     sync.Mutex
	events []bridgeEvent
	ready  chan struct{}
	once   sync.Once
}

func TestStatusJSONUsesStableNamesAndContainsOnlyBoundedPublicState(t *testing.T) {
	payload, err := marshalSnapshot(bridgeSnapshot{
		Status: statusConnected,
		URL:    "https://example.trycloudflare.com",
		Error:  "",
	})
	if err != nil {
		t.Fatal(err)
	}
	var decoded map[string]string
	if err := json.Unmarshal(payload, &decoded); err != nil {
		t.Fatalf("status is not JSON: %v", err)
	}
	want := map[string]string{
		"status": "CONNECTED",
		"url":    "https://example.trycloudflare.com",
		"error":  "",
	}
	for key, value := range want {
		if decoded[key] != value {
			t.Fatalf("status[%q] = %q, want %q", key, decoded[key], value)
		}
	}
	if len(decoded) != len(want) {
		t.Fatalf("unexpected status fields: %#v", decoded)
	}
}

func newEventRecorder() *eventRecorder {
	return &eventRecorder{ready: make(chan struct{})}
}

func (r *eventRecorder) record(event bridgeEvent) {
	r.mu.Lock()
	r.events = append(r.events, event)
	r.mu.Unlock()
	if event.Status == statusConnected {
		r.once.Do(func() { close(r.ready) })
	}
}

func (r *eventRecorder) snapshot() []bridgeEvent {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]bridgeEvent(nil), r.events...)
}

func testCredentials() connection.Credentials {
	return connection.Credentials{
		AccountTag:   "account-secret",
		TunnelSecret: []byte("tunnel-secret"),
		TunnelID:     uuid.MustParse("d8d8fa75-d6cb-4615-a09b-187ae29908fa"),
	}
}

func TestQuickHandlePublishesConnectedURLAndStopWaitsForTransport(t *testing.T) {
	recorder := newEventRecorder()
	transportExited := make(chan struct{})
	request := func(context.Context) (quickTunnel, error) {
		return quickTunnel{
			URL:         "https://example.trycloudflare.com",
			Credentials: testCredentials(),
		}, nil
	}
	run := func(ctx context.Context, _ string, quick quickTunnel, observer tunnelEventObserver) error {
		observer.connected(quick.URL)
		<-ctx.Done()
		close(transportExited)
		observer.disconnected()
		return ctx.Err()
	}

	handle := startQuickTunnel("http://127.0.0.1:8080", recorder.record, request, run)
	select {
	case <-recorder.ready:
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for connected callback")
	}

	if got := handle.stop(); got != resultOK {
		t.Fatalf("stop result = %d, want %d", got, resultOK)
	}
	select {
	case <-transportExited:
	default:
		t.Fatal("stop returned before transport goroutine exited")
	}

	events := recorder.snapshot()
	if len(events) < 3 {
		t.Fatalf("events = %#v, want at least starting, connected, stopped", events)
	}
	if events[0].Status != statusStarting {
		t.Fatalf("first event = %#v, want starting", events[0])
	}
	connected := events[1]
	if connected.Status != statusConnected || connected.URL != "https://example.trycloudflare.com" {
		t.Fatalf("connected event = %#v", connected)
	}
	if last := events[len(events)-1]; last.Status != statusStopped {
		t.Fatalf("last event = %#v, want stopped", last)
	}

	snapshot := handle.snapshot()
	if snapshot.Status != statusStopped || snapshot.URL != "" || snapshot.Error != "" {
		t.Fatalf("snapshot after stop = %#v", snapshot)
	}
}

func TestQuickHandleRejectsNonLoopbackOriginBeforeRequest(t *testing.T) {
	recorder := newEventRecorder()
	requestCalled := false
	request := func(context.Context) (quickTunnel, error) {
		requestCalled = true
		return quickTunnel{}, errors.New("must not be called")
	}

	handle := startQuickTunnel("http://192.0.2.10:8080", recorder.record, request, nil)
	handle.wait()

	if requestCalled {
		t.Fatal("quick tunnel credentials were requested for a non-loopback origin")
	}
	snapshot := handle.snapshot()
	if snapshot.Status != statusFailed || !strings.Contains(snapshot.Error, "loopback") {
		t.Fatalf("snapshot = %#v, want loopback validation failure", snapshot)
	}
}

func TestStopDuringCredentialRequestDoesNotReportFailure(t *testing.T) {
	recorder := newEventRecorder()
	requestStarted := make(chan struct{})
	request := func(ctx context.Context) (quickTunnel, error) {
		close(requestStarted)
		<-ctx.Done()
		return quickTunnel{}, ctx.Err()
	}
	handle := startQuickTunnel(
		"http://127.0.0.1:8080",
		recorder.record,
		request,
		func(context.Context, string, quickTunnel, tunnelEventObserver) error { return nil },
	)
	<-requestStarted

	handle.stop()

	events := recorder.snapshot()
	for _, event := range events {
		if event.Status == statusFailed {
			t.Fatalf("cancellation emitted failure callback: %#v", events)
		}
	}
	if last := events[len(events)-1]; last.Status != statusStopped {
		t.Fatalf("last event = %#v, want stopped", last)
	}
}

func TestObserverTransitionsAreRejectedAfterStoppingBegins(t *testing.T) {
	recorder := newEventRecorder()
	stopBegan := make(chan struct{})
	var stopOnce sync.Once
	callback := func(event bridgeEvent) {
		recorder.record(event)
		if event.Status == statusStopping {
			stopOnce.Do(func() { close(stopBegan) })
		}
	}
	observerReady := make(chan tunnelEventObserver, 1)
	allowTransportExit := make(chan struct{})
	run := func(ctx context.Context, _ string, _ quickTunnel, observer tunnelEventObserver) error {
		observerReady <- observer
		<-ctx.Done()
		<-allowTransportExit
		return ctx.Err()
	}
	handle := startQuickTunnel(
		"http://127.0.0.1:8080",
		callback,
		func(context.Context) (quickTunnel, error) {
			return quickTunnel{URL: "https://example.trycloudflare.com", Credentials: testCredentials()}, nil
		},
		run,
	)
	observer := <-observerReady
	stopReturned := make(chan struct{})
	go func() {
		handle.stop()
		close(stopReturned)
	}()
	select {
	case <-stopBegan:
	case <-time.After(time.Second):
		t.Fatal("stop did not publish STOPPING")
	}

	observerCalls := sync.WaitGroup{}
	observerCalls.Add(2)
	go func() {
		defer observerCalls.Done()
		observer.connected("https://late.trycloudflare.com")
	}()
	go func() {
		defer observerCalls.Done()
		observer.reconnecting()
	}()
	observerCalls.Wait()
	close(allowTransportExit)
	select {
	case <-stopReturned:
	case <-time.After(time.Second):
		t.Fatal("stop did not return")
	}

	events := recorder.snapshot()
	stoppingSeen := false
	for _, event := range events {
		if event.Status == statusStopping {
			stoppingSeen = true
			continue
		}
		if stoppingSeen && (event.Status == statusConnected || event.Status == statusReconnecting) {
			t.Fatalf("non-terminal observer transition after STOPPING: %#v", events)
		}
	}
	if last := events[len(events)-1]; last.Status != statusStopped {
		t.Fatalf("last event = %#v, want stopped", last)
	}
}

func TestExternalStopWaitsForCallbackAndClosesDispatch(t *testing.T) {
	callbackEntered := make(chan struct{})
	releaseCallback := make(chan struct{})
	callbackReturned := make(chan struct{})
	var callbackOnce sync.Once
	var callbackInvocations atomic.Int32
	callback := func(event bridgeEvent) {
		if event.Status != statusConnected {
			return
		}
		callbackInvocations.Add(1)
		callbackOnce.Do(func() {
			close(callbackEntered)
			<-releaseCallback
			close(callbackReturned)
		})
	}
	run := func(ctx context.Context, _ string, quick quickTunnel, observer tunnelEventObserver) error {
		observer.connected(quick.URL)
		<-ctx.Done()
		return ctx.Err()
	}
	handle := startQuickTunnel(
		"http://127.0.0.1:8080",
		callback,
		func(context.Context) (quickTunnel, error) {
			return quickTunnel{URL: "https://example.trycloudflare.com", Credentials: testCredentials()}, nil
		},
		run,
	)
	<-callbackEntered

	stopReturned := make(chan struct{})
	go func() {
		handle.stop()
		close(stopReturned)
	}()
	select {
	case <-stopReturned:
		t.Fatal("external stop returned while a callback still owned user state")
	case <-time.After(50 * time.Millisecond):
	}
	close(releaseCallback)
	select {
	case <-callbackReturned:
	case <-time.After(time.Second):
		t.Fatal("callback did not return")
	}
	select {
	case <-stopReturned:
	case <-time.After(time.Second):
		t.Fatal("external stop did not drain callback dispatch")
	}

	handle.publish(bridgeEvent{Status: statusConnected, URL: "https://late.trycloudflare.com"})
	time.Sleep(20 * time.Millisecond)
	if got := callbackInvocations.Load(); got != 1 {
		t.Fatalf("callback invocations after stop = %d, want 1", got)
	}
}

func TestStopFromConnectedCallbackDoesNotDeadlock(t *testing.T) {
	handleAssigned := make(chan struct{})
	stopReturned := make(chan int, 1)
	var handle *tunnelHandle
	callback := func(event bridgeEvent) {
		if event.Status == statusConnected {
			<-handleAssigned
			stopReturned <- handle.stopFromCallback()
		}
	}
	run := func(ctx context.Context, _ string, quick quickTunnel, observer tunnelEventObserver) error {
		observer.connected(quick.URL)
		<-ctx.Done()
		return ctx.Err()
	}
	handle = startQuickTunnel(
		"http://127.0.0.1:8080",
		callback,
		func(context.Context) (quickTunnel, error) {
			return quickTunnel{URL: "https://example.trycloudflare.com", Credentials: testCredentials()}, nil
		},
		run,
	)
	close(handleAssigned)

	select {
	case result := <-stopReturned:
		if result != resultOK {
			t.Fatalf("reentrant stop result = %d, want %d", result, resultOK)
		}
	case <-time.After(time.Second):
		t.Fatal("stop called from connected callback deadlocked")
	}
	select {
	case <-handle.callbacksDone():
	case <-time.After(time.Second):
		t.Fatal("callback dispatcher did not terminate after reentrant stop returned")
	}
}

func TestLoginCallbackUsesOwnedDispatcher(t *testing.T) {
	handle := newTunnelHandle(nil)
	callbackEntered := make(chan struct{})
	releaseCallback := make(chan struct{})
	beginReturned := make(chan int, 1)
	go func() {
		beginReturned <- handle.beginLogin(func(bridgeEvent) {
			close(callbackEntered)
			<-releaseCallback
		})
	}()
	<-callbackEntered

	select {
	case result := <-beginReturned:
		if result != resultUnsupported {
			t.Fatalf("begin login result = %d, want %d", result, resultUnsupported)
		}
	case <-time.After(50 * time.Millisecond):
		t.Fatal("begin login executed its callback inline")
	}
	stopReturned := make(chan struct{})
	go func() {
		handle.stop()
		close(stopReturned)
	}()
	select {
	case <-stopReturned:
		t.Fatal("stop returned while login callback was active")
	case <-time.After(50 * time.Millisecond):
	}
	close(releaseCallback)
	select {
	case <-stopReturned:
	case <-time.After(time.Second):
		t.Fatal("stop did not drain login callback")
	}
}

func TestQuickHandleRedactsAndBoundsCredentialBearingErrors(t *testing.T) {
	recorder := newEventRecorder()
	credentials := testCredentials()
	encodedSecret := base64.StdEncoding.EncodeToString(credentials.TunnelSecret)
	request := func(context.Context) (quickTunnel, error) {
		return quickTunnel{
			URL:         "https://example.trycloudflare.com",
			Credentials: credentials,
		}, nil
	}
	run := func(context.Context, string, quickTunnel, tunnelEventObserver) error {
		return errors.New("account-secret tunnel-secret " + encodedSecret + " " + strings.Repeat("x", maxErrorBytes))
	}

	handle := startQuickTunnel("http://127.0.0.1:8080", recorder.record, request, run)
	handle.wait()
	snapshot := handle.snapshot()

	if snapshot.Status != statusFailed {
		t.Fatalf("status = %v, want failed", snapshot.Status)
	}
	if len(snapshot.Error) > maxErrorBytes {
		t.Fatalf("error length = %d, max = %d", len(snapshot.Error), maxErrorBytes)
	}
	for _, secret := range []string{"account-secret", "tunnel-secret", encodedSecret} {
		if strings.Contains(snapshot.Error, secret) {
			t.Fatalf("error contains credential %q: %q", secret, snapshot.Error)
		}
	}
	for _, event := range recorder.snapshot() {
		if len(event.Error) > maxErrorBytes {
			t.Fatalf("callback error length = %d, max = %d", len(event.Error), maxErrorBytes)
		}
	}
}

func TestAuthenticatedFacadeOperationsAreExplicitlyUnsupported(t *testing.T) {
	handle := newTunnelHandle(nil)
	if got := handle.beginLogin(nil); got != resultUnsupported {
		t.Fatalf("beginLogin result = %d, want unsupported", got)
	}
	if got := handle.selectExisting("tunnel-id", "public.example.com"); got != resultUnsupported {
		t.Fatalf("selectExisting result = %d, want unsupported", got)
	}
	handle.stop()
}

func encodedTestToken(t *testing.T, mutate func(*connection.TunnelToken)) string {
	t.Helper()
	token := connection.TunnelToken{
		AccountTag:   "account-secret",
		TunnelSecret: []byte("0123456789abcdef0123456789abcdef"),
		TunnelID:     uuid.MustParse("d8d8fa75-d6cb-4615-a09b-187ae29908fa"),
	}
	if mutate != nil {
		mutate(&token)
	}
	payload, err := json.Marshal(token)
	if err != nil {
		t.Fatal(err)
	}
	return base64.StdEncoding.EncodeToString(payload)
}

func TestParseTunnelTokenRejectsMalformedAndOversizeInputWithoutEchoingIt(t *testing.T) {
	tests := []string{
		"not base64 @@ token-secret",
		base64.StdEncoding.EncodeToString([]byte(`{"a":"account","s":"c2VjcmV0","t":"not-a-uuid"}`)),
		strings.Repeat("x", maxTokenBytes+1),
		encodedTestToken(t, func(token *connection.TunnelToken) { token.AccountTag = "" }),
		encodedTestToken(t, func(token *connection.TunnelToken) { token.AccountTag = "bad account" }),
		encodedTestToken(t, func(token *connection.TunnelToken) { token.TunnelSecret = nil }),
		encodedTestToken(t, func(token *connection.TunnelToken) { token.TunnelID = uuid.Nil }),
		encodedTestToken(t, func(token *connection.TunnelToken) { token.Endpoint = "bad endpoint/secret" }),
	}
	for _, raw := range tests {
		_, err := parseTunnelToken(raw)
		if err == nil {
			t.Fatalf("parseTunnelToken(%d bytes) succeeded", len(raw))
		}
		if strings.Contains(err.Error(), raw) || strings.Contains(err.Error(), "account-secret") {
			t.Fatalf("parse error leaked token material: %q", err)
		}
	}
}

func TestTokenHandleRunsDecodedCredentialsAndCancels(t *testing.T) {
	recorder := newEventRecorder()
	token := encodedTestToken(t, func(token *connection.TunnelToken) { token.Endpoint = "fed" })
	runnerStarted := make(chan connection.Credentials, 1)
	runnerExited := make(chan struct{})
	run := func(ctx context.Context, origin string, tunnel quickTunnel, observer tunnelEventObserver) error {
		if origin != "http://127.0.0.1:3000" {
			t.Fatalf("origin = %q", origin)
		}
		runnerStarted <- tunnel.Credentials
		observer.connected("")
		<-ctx.Done()
		close(runnerExited)
		return ctx.Err()
	}

	handle := startTokenTunnel(token, "http://127.0.0.1:3000", recorder.record, run)
	credentials := <-runnerStarted
	if credentials.AccountTag != "account-secret" ||
		credentials.TunnelID != uuid.MustParse("d8d8fa75-d6cb-4615-a09b-187ae29908fa") ||
		credentials.Endpoint != "fed" ||
		string(credentials.TunnelSecret) != "0123456789abcdef0123456789abcdef" {
		t.Fatalf("decoded credentials = %#v", credentials)
	}
	if got := handle.stop(); got != resultOK {
		t.Fatalf("stop result = %d", got)
	}
	select {
	case <-runnerExited:
	default:
		t.Fatal("stop returned before token runner exited")
	}
	for _, event := range recorder.snapshot() {
		if strings.Contains(event.Error, token) || strings.Contains(event.Error, "account-secret") {
			t.Fatalf("event leaked credentials: %#v", event)
		}
	}
}

func TestTokenHandleRedactsCredentialsFromRunnerFailure(t *testing.T) {
	recorder := newEventRecorder()
	token := encodedTestToken(t, nil)
	handle := startTokenTunnel(
		token,
		"http://127.0.0.1:3000",
		recorder.record,
		func(context.Context, string, quickTunnel, tunnelEventObserver) error {
			return errors.New("account-secret 0123456789abcdef0123456789abcdef " + token)
		},
	)
	handle.wait()
	snapshot := handle.snapshot()
	if snapshot.Status != statusFailed {
		t.Fatalf("snapshot = %#v, want failed", snapshot)
	}
	for _, secret := range []string{"account-secret", "0123456789abcdef0123456789abcdef", token} {
		if strings.Contains(snapshot.Error, secret) {
			t.Fatalf("failure leaked %q: %q", secret, snapshot.Error)
		}
	}
}

func TestTokenHandleCanRepeatStartAndStop(t *testing.T) {
	token := encodedTestToken(t, nil)
	for iteration := 0; iteration < 3; iteration++ {
		runnerStarted := make(chan struct{})
		handle := startTokenTunnel(
			token,
			"http://127.0.0.1:3000",
			nil,
			func(ctx context.Context, _ string, _ quickTunnel, observer tunnelEventObserver) error {
				close(runnerStarted)
				observer.connected("")
				<-ctx.Done()
				return ctx.Err()
			},
		)
		<-runnerStarted
		if got := handle.stop(); got != resultOK {
			t.Fatalf("iteration %d stop result = %d", iteration, got)
		}
		if snapshot := handle.snapshot(); snapshot.Status != statusStopped {
			t.Fatalf("iteration %d snapshot = %#v", iteration, snapshot)
		}
	}
}
