package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
	"unicode/utf16"

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
	var runnerSecretAlias []byte
	request := func(context.Context) (quickTunnel, error) {
		return quickTunnel{
			URL:         "https://example.trycloudflare.com",
			Credentials: testCredentials(),
		}, nil
	}
	run := func(ctx context.Context, _ string, quick quickTunnel, observer tunnelEventObserver) error {
		runnerSecretAlias = quick.Credentials.TunnelSecret
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
	for index, value := range runnerSecretAlias {
		if value != 0 {
			t.Fatalf("quick-stop tunnel secret byte %d was not wiped", index)
		}
	}
}

func TestQuickHandleWipesRunnerCredentialAliasOnNormalReturn(t *testing.T) {
	var runnerSecretAlias []byte
	handle := startQuickTunnel(
		"http://127.0.0.1:8080",
		nil,
		func(context.Context) (quickTunnel, error) {
			return quickTunnel{URL: "https://normal.example.com", Credentials: testCredentials()}, nil
		},
		func(_ context.Context, _ string, quick quickTunnel, _ tunnelEventObserver) error {
			runnerSecretAlias = quick.Credentials.TunnelSecret
			return nil
		},
	)
	handle.wait()
	for index, value := range runnerSecretAlias {
		if value != 0 {
			t.Fatalf("quick-normal tunnel secret byte %d was not wiped", index)
		}
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
	var callbackOnce sync.Once
	beginReturned := make(chan int, 1)
	go func() {
		beginReturned <- handle.beginLogin(func(bridgeEvent) {
			callbackOnce.Do(func() {
				close(callbackEntered)
				<-releaseCallback
			})
		}, fakeLoginTransfer{
			authorizationURL: "https://dash.cloudflare.com/login-test",
			poll: func(context.Context) ([]byte, error) {
				return originCertificate(t, "account-id", "api-secret"), nil
			},
		}, defaultAuthAPIFactory(roundTripFunc(func(*http.Request) (*http.Response, error) {
			return nil, errors.New("not used")
		})), nil)
	}()
	<-callbackEntered

	select {
	case result := <-beginReturned:
		if result != resultOK {
			t.Fatalf("begin login result = %d, want %d", result, resultOK)
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
	var runnerSecretAlias []byte
	encodedSecret := base64.StdEncoding.EncodeToString(credentials.TunnelSecret)
	request := func(context.Context) (quickTunnel, error) {
		return quickTunnel{
			URL:         "https://example.trycloudflare.com",
			Credentials: credentials,
		}, nil
	}
	run := func(_ context.Context, _ string, quick quickTunnel, _ tunnelEventObserver) error {
		runnerSecretAlias = quick.Credentials.TunnelSecret
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
	for index, value := range runnerSecretAlias {
		if value != 0 {
			t.Fatalf("quick-error tunnel secret byte %d was not wiped", index)
		}
	}
}

func TestAuthenticatedFacadeUsesSelectedTunnelAndHostnameWithoutSwitchingConnector(t *testing.T) {
	selectedRunToken := encodedTestToken(t, func(token *connection.TunnelToken) {
		token.AccountTag = "account-id"
	})
	var requestedTokenPath string
	var tokenBodyClosed atomic.Bool
	client := roundTripFunc(func(request *http.Request) (*http.Response, error) {
		if strings.HasSuffix(request.URL.Path, "/token") {
			requestedTokenPath = request.URL.Path
			return &http.Response{
				StatusCode: http.StatusOK,
				Header:     make(http.Header),
				Body: &observedReadCloser{
					Reader: strings.NewReader(`{"success":true,"result":"` + selectedRunToken + `"}`),
					closed: &tokenBodyClosed,
				},
			}, nil
		}
		return response(http.StatusOK, `{
            "success":true,
            "result":[{"id":"d8d8fa75-d6cb-4615-a09b-187ae29908fa","name":"receipts","deleted_at":null,"remote_config":false}],
            "result_info":{"page":1,"per_page":100,"count":1,"total_count":1}
        }`), nil
	})
	handle := newTunnelHandle(nil)
	if got := handle.beginLogin(nil, fakeLoginTransfer{
		authorizationURL: "https://dash.cloudflare.com/login-test",
		poll: func(context.Context) ([]byte, error) {
			return originCertificate(t, "account-id", "api-secret"), nil
		},
	}, defaultAuthAPIFactory(client), nil); got != resultOK {
		t.Fatalf("beginLogin result = %d, want OK", got)
	}
	waitForAuthState(t, handle.authSnapshot(), authAuthorized)
	if got := handle.selectExisting(
		"d8d8fa75-d6cb-4615-a09b-187ae29908fa",
		"https://Receipts.Example.com/",
	); got != resultOK {
		t.Fatalf("selectExisting result = %d, want OK", got)
	}
	if requestedTokenPath != "/client/v4/accounts/account-id/cfd_tunnel/d8d8fa75-d6cb-4615-a09b-187ae29908fa/token" {
		t.Fatalf("token path = %q", requestedTokenPath)
	}
	if !tokenBodyClosed.Load() {
		t.Fatal("token response body was not closed")
	}
	auth := handle.authSnapshot().snapshot()
	if auth.SelectedTunnelID != "d8d8fa75-d6cb-4615-a09b-187ae29908fa" ||
		auth.SelectedHostname != "https://receipts.example.com" {
		t.Fatalf("selection state = %#v", auth)
	}
	if snapshot := handle.snapshot(); snapshot.Status != statusStopped {
		t.Fatalf("connector status changed during auth selection: %#v", snapshot)
	}
	for _, snapshot := range []any{handle.snapshot(), auth} {
		payload, err := json.Marshal(snapshot)
		if err != nil {
			t.Fatal(err)
		}
		if strings.Contains(string(payload), selectedRunToken) || strings.Contains(string(payload), "api-secret") {
			t.Fatalf("public snapshot leaked auth secret: %s", payload)
		}
	}
	handle.stop()
}

func TestAttachedAuthReplacementSuppressesLateOldTerminalCallback(t *testing.T) {
	handle := newTunnelHandle(nil)
	oldPollEntered := make(chan struct{})
	oldPollCancelled := make(chan struct{})
	releaseOldPoll := make(chan struct{})
	oldEvents := make(chan bridgeEvent, 4)
	if got := handle.beginLogin(func(event bridgeEvent) {
		oldEvents <- event
	}, fakeLoginTransfer{
		authorizationURL: "https://dash.cloudflare.com/old-login",
		poll: func(ctx context.Context) ([]byte, error) {
			close(oldPollEntered)
			<-ctx.Done()
			close(oldPollCancelled)
			<-releaseOldPoll
			return nil, ctx.Err()
		},
	}, defaultAuthAPIFactory(roundTripFunc(func(*http.Request) (*http.Response, error) {
		return nil, errors.New("unexpected API request")
	})), nil); got != resultOK {
		t.Fatalf("old begin result = %d", got)
	}
	<-oldPollEntered
	if event := receiveBridgeEvent(t, oldEvents); event.Status != statusStarting {
		t.Fatalf("old initial event = %#v", event)
	}

	replacementReturned := make(chan int, 1)
	newAuthorizationStarted := make(chan struct{})
	go func() {
		replacementReturned <- handle.beginLogin(nil, observedLoginTransfer{
			authorize: func() string {
				close(newAuthorizationStarted)
				return "https://dash.cloudflare.com/new-login"
			},
			poll: func(ctx context.Context) ([]byte, error) {
				<-ctx.Done()
				return nil, ctx.Err()
			},
		}, defaultAuthAPIFactory(roundTripFunc(func(*http.Request) (*http.Response, error) {
			return nil, errors.New("unexpected API request")
		})), nil)
	}()
	<-oldPollCancelled
	select {
	case <-newAuthorizationStarted:
		t.Fatal("new auth started before prior poll exited")
	default:
	}
	close(releaseOldPoll)
	if result := <-replacementReturned; result != resultOK {
		t.Fatalf("replacement result = %d", result)
	}
	select {
	case <-newAuthorizationStarted:
	default:
		t.Fatal("new auth did not start after prior poll exited")
	}
	select {
	case event := <-oldEvents:
		t.Fatalf("old auth callback published after replacement: %#v", event)
	case <-time.After(50 * time.Millisecond):
	}
	handle.stop()
}

func TestAttachedAuthPublishesStartingBeforeTerminalForEachGeneration(t *testing.T) {
	handle := newTunnelHandle(nil)
	events := make(chan bridgeEvent, 4)
	if got := handle.beginLogin(func(event bridgeEvent) {
		events <- event
	}, fakeLoginTransfer{
		authorizationURL: "https://dash.cloudflare.com/fast-login",
		poll: func(context.Context) ([]byte, error) {
			return originCertificate(t, "account-id", "api-secret"), nil
		},
	}, defaultAuthAPIFactory(roundTripFunc(func(*http.Request) (*http.Response, error) {
		return nil, errors.New("unexpected API request")
	})), nil); got != resultOK {
		t.Fatalf("begin result = %d", got)
	}
	if first := receiveBridgeEvent(t, events); first.Status != statusStarting {
		t.Fatalf("first auth event = %#v, want STARTING", first)
	}
	if second := receiveBridgeEvent(t, events); second.Status != statusConnected {
		t.Fatalf("terminal auth event = %#v, want CONNECTED", second)
	}
	handle.stop()
}

func TestAttachedAuthStopRacingReplacementCannotPublishNewGeneration(t *testing.T) {
	handle := newTunnelHandle(nil)
	oldCancelled := make(chan struct{})
	releaseOld := make(chan struct{})
	if got := handle.beginLogin(nil, fakeLoginTransfer{
		authorizationURL: "https://dash.cloudflare.com/old",
		poll: func(ctx context.Context) ([]byte, error) {
			<-ctx.Done()
			close(oldCancelled)
			<-releaseOld
			return nil, ctx.Err()
		},
	}, defaultAuthAPIFactory(roundTripFunc(func(*http.Request) (*http.Response, error) {
		return nil, errors.New("unexpected API request")
	})), nil); got != resultOK {
		t.Fatalf("old begin result = %d", got)
	}
	newStarted := make(chan struct{})
	replacementReturned := make(chan int, 1)
	go func() {
		replacementReturned <- handle.beginLogin(nil, observedLoginTransfer{
			authorize: func() string {
				close(newStarted)
				return "https://dash.cloudflare.com/new"
			},
			poll: func(ctx context.Context) ([]byte, error) {
				<-ctx.Done()
				return nil, ctx.Err()
			},
		}, defaultAuthAPIFactory(roundTripFunc(func(*http.Request) (*http.Response, error) {
			return nil, errors.New("unexpected API request")
		})), nil)
	}()
	<-oldCancelled
	stopReturned := make(chan struct{})
	go func() {
		handle.stop()
		close(stopReturned)
	}()
	<-handle.ctx.Done()
	close(releaseOld)
	if result := <-replacementReturned; result != resultInvalid {
		t.Fatalf("replacement result = %d, want invalid", result)
	}
	select {
	case <-newStarted:
		t.Fatal("replacement published a new auth after stop began")
	default:
	}
	select {
	case <-stopReturned:
	case <-time.After(time.Second):
		t.Fatal("stop did not return")
	}
}

func TestAttachedAuthStopDrainsPollAndClosesHTTPTransport(t *testing.T) {
	handle := newTunnelHandle(nil)
	pollEntered := make(chan struct{})
	pollExited := make(chan struct{})
	transportClosed := make(chan struct{})
	if got := handle.beginLogin(nil, fakeLoginTransfer{
		authorizationURL: "https://dash.cloudflare.com/login",
		poll: func(ctx context.Context) ([]byte, error) {
			close(pollEntered)
			<-ctx.Done()
			close(pollExited)
			return nil, ctx.Err()
		},
	}, defaultAuthAPIFactory(roundTripFunc(func(*http.Request) (*http.Response, error) {
		return nil, errors.New("unexpected API request")
	})), func() { close(transportClosed) }); got != resultOK {
		t.Fatalf("begin result = %d", got)
	}
	<-pollEntered
	if got := handle.stop(); got != resultOK {
		t.Fatalf("stop result = %d", got)
	}
	select {
	case <-pollExited:
	default:
		t.Fatal("stop returned before auth poll exited")
	}
	select {
	case <-transportClosed:
	default:
		t.Fatal("stop returned before auth HTTP transport closed")
	}
}

func TestIndependentAuthRegistryIsTypeIsolatedFromConnectorRegistry(t *testing.T) {
	connector := newTunnelHandle(nil)
	connectorPointer := registerHandle(connector, newCallbackIdentity())
	if connectorPointer == nil {
		t.Fatal("connector registration failed")
	}
	auth := newIndependentAuthHandle(fakeLoginTransfer{
		authorizationURL: "https://dash.cloudflare.com/independent",
		poll: func(ctx context.Context) ([]byte, error) {
			<-ctx.Done()
			return nil, ctx.Err()
		},
	}, defaultAuthAPIFactory(roundTripFunc(func(*http.Request) (*http.Response, error) {
		return nil, errors.New("unexpected API request")
	})), nil)
	authPointer := registerAuthHandle(auth)
	if authPointer == 0 {
		connector.stop()
		finalizeHandle(connectorPointer, connector)
		t.Fatal("auth registration failed")
	}
	if lookupAuthHandle(uint64(uintptr(connectorPointer))) != nil {
		t.Fatal("connector pointer resolved through auth registry")
	}
	if lookupHandle(connectorPointer) != connector || lookupAuthHandle(authPointer) != auth {
		t.Fatal("registered handle did not resolve through its own typed registry")
	}
	if !closeRegisteredAuthHandle(authPointer) {
		t.Fatal("auth close failed")
	}
	connector.stop()
	finalizeHandle(connectorPointer, connector)
}

func TestIndependentAuthCancelAndConnectorStopAreIsolated(t *testing.T) {
	connectorExited := make(chan struct{})
	connector := startQuickTunnel(
		"http://127.0.0.1:8080",
		nil,
		func(context.Context) (quickTunnel, error) {
			return quickTunnel{URL: "https://connector.example.com", Credentials: testCredentials()}, nil
		},
		func(ctx context.Context, _ string, _ quickTunnel, _ tunnelEventObserver) error {
			<-ctx.Done()
			close(connectorExited)
			return ctx.Err()
		},
	)
	authExited := make(chan struct{})
	auth := newIndependentAuthHandle(fakeLoginTransfer{
		authorizationURL: "https://dash.cloudflare.com/independent",
		poll: func(ctx context.Context) ([]byte, error) {
			<-ctx.Done()
			close(authExited)
			return nil, ctx.Err()
		},
	}, defaultAuthAPIFactory(roundTripFunc(func(*http.Request) (*http.Response, error) {
		return nil, errors.New("unexpected API request")
	})), nil)
	authPointer := registerAuthHandle(auth)
	if !closeRegisteredAuthHandle(authPointer) {
		t.Fatal("auth cancel failed")
	}
	select {
	case <-authExited:
	default:
		t.Fatal("auth cancel returned before auth poll exited")
	}
	select {
	case <-connectorExited:
		t.Fatal("auth cancel stopped connector")
	default:
	}
	connector.stop()
	select {
	case <-connectorExited:
	default:
		t.Fatal("connector stop did not drain connector")
	}

	secondAuthExited := make(chan struct{})
	secondAuth := newIndependentAuthHandle(fakeLoginTransfer{
		authorizationURL: "https://dash.cloudflare.com/second",
		poll: func(ctx context.Context) ([]byte, error) {
			<-ctx.Done()
			close(secondAuthExited)
			return nil, ctx.Err()
		},
	}, defaultAuthAPIFactory(roundTripFunc(func(*http.Request) (*http.Response, error) {
		return nil, errors.New("unexpected API request")
	})), nil)
	secondPointer := registerAuthHandle(secondAuth)
	secondConnector := newTunnelHandle(nil)
	secondConnector.stop()
	select {
	case <-secondAuthExited:
		t.Fatal("connector stop cancelled independent auth")
	default:
	}
	if !closeRegisteredAuthHandle(secondPointer) {
		t.Fatal("second auth cancel failed")
	}
}

func TestIndependentAuthJSONIsBoundedSecretFreeAndSelectAloneReturnsToken(t *testing.T) {
	runToken := encodedTestToken(t, func(token *connection.TunnelToken) {
		token.AccountTag = "account-id"
	})
	client := roundTripFunc(func(request *http.Request) (*http.Response, error) {
		switch {
		case strings.Contains(request.URL.Host, "login.cloudflareaccess.org"):
			return response(http.StatusOK, string(originCertificate(t, "account-id", "api-secret"))), nil
		case strings.HasSuffix(request.URL.Path, "/token"):
			return response(http.StatusOK, `{"success":true,"result":"`+runToken+`"}`), nil
		default:
			return response(http.StatusOK, `{
                    "success":true,
                    "result":[{"id":"d8d8fa75-d6cb-4615-a09b-187ae29908fa","name":"receipts","deleted_at":null,"remote_config":false}],
                    "result_info":{"page":1,"per_page":100,"count":1,"total_count":1}
                }`), nil
		}
	})
	transfer, err := newLoginTransfer(client, bytesReader32(13))
	if err != nil {
		t.Fatal(err)
	}
	transportClosed := atomic.Bool{}
	auth := newIndependentAuthHandle(transfer, defaultAuthAPIFactory(client), func() {
		transportClosed.Store(true)
	})
	waitForAuthState(t, auth.session, authAuthorized)
	statusPayload, err := auth.statusJSON()
	if err != nil {
		t.Fatal(err)
	}
	listPayload, err := auth.listJSON(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	for _, payload := range [][]byte{statusPayload, listPayload} {
		if len(payload) == 0 || len(payload) > maxAuthJSONBytes {
			t.Fatalf("auth JSON length = %d", len(payload))
		}
		for _, secret := range []string{"api-secret", runToken, "0123456789abcdef0123456789abcdef"} {
			if strings.Contains(string(payload), secret) {
				t.Fatalf("auth JSON leaked %q: %s", secret, payload)
			}
		}
	}
	selected, err := auth.selectToken(
		context.Background(),
		"d8d8fa75-d6cb-4615-a09b-187ae29908fa",
		"https://receipts.example.com",
	)
	if err != nil {
		t.Fatal(err)
	}
	if selected != runToken {
		t.Fatal("private select did not return the run token")
	}
	auth.close()
	if !transportClosed.Load() {
		t.Fatal("auth close did not close HTTP transport")
	}
}

func TestIndependentAuthRegistryCancelDrainsInFlightListBeforeFree(t *testing.T) {
	listEntered := make(chan struct{})
	listExited := make(chan struct{})
	client := roundTripFunc(func(request *http.Request) (*http.Response, error) {
		if strings.Contains(request.URL.Host, "login.cloudflareaccess.org") {
			return response(http.StatusOK, string(originCertificate(t, "account-id", "api-secret"))), nil
		}
		close(listEntered)
		<-request.Context().Done()
		close(listExited)
		return nil, request.Context().Err()
	})
	transfer, err := newLoginTransfer(client, bytesReader32(14))
	if err != nil {
		t.Fatal(err)
	}
	auth := newIndependentAuthHandle(transfer, defaultAuthAPIFactory(client), nil)
	waitForAuthState(t, auth.session, authAuthorized)
	pointer := registerAuthHandle(auth)
	listReturned := make(chan struct{})
	go func() {
		_, _ = lookupAuthHandle(pointer).listJSON(context.Background())
		close(listReturned)
	}()
	<-listEntered
	if !closeRegisteredAuthHandle(pointer) {
		t.Fatal("auth cancel failed")
	}
	select {
	case <-listExited:
	default:
		t.Fatal("cancel returned before list transport exited")
	}
	select {
	case <-listReturned:
	case <-time.After(time.Second):
		t.Fatal("cancelled list did not return")
	}
	if lookupAuthHandle(pointer) != nil {
		t.Fatal("cancelled auth remained registered")
	}
}

func TestIndependentAuthRegistryNeverReusesClosedOpaqueID(t *testing.T) {
	newWaitingAuth := func() *independentAuthHandle {
		return newIndependentAuthHandle(fakeLoginTransfer{
			authorizationURL: "https://dash.cloudflare.com/opaque-id",
			poll: func(ctx context.Context) ([]byte, error) {
				<-ctx.Done()
				return nil, ctx.Err()
			},
		}, defaultAuthAPIFactory(roundTripFunc(func(*http.Request) (*http.Response, error) {
			return nil, errors.New("unexpected API request")
		})), nil)
	}
	first := newWaitingAuth()
	var staleID uint64 = registerAuthHandle(first)
	if !closeRegisteredAuthHandle(staleID) {
		t.Fatal("first auth close failed")
	}
	second := newWaitingAuth()
	var currentID uint64 = registerAuthHandle(second)
	if currentID == staleID {
		t.Fatal("closed auth opaque ID was reused")
	}
	if lookupAuthHandle(staleID) != nil || closeRegisteredAuthHandle(staleID) {
		t.Fatal("stale auth ID resolved or closed the replacement")
	}
	if lookupAuthHandle(currentID) != second {
		t.Fatal("stale ID operation disturbed current auth")
	}
	if !closeRegisteredAuthHandle(currentID) {
		t.Fatal("second auth close failed")
	}
}

func TestEncodeJNIUTF16PreservesSupplementaryPlaneJSON(t *testing.T) {
	payload := []byte(`{"name":"隧道🚀"}`)
	encoded, err := encodeJNIUTF16(payload)
	if err != nil {
		t.Fatal(err)
	}
	decoded := string(utf16.Decode(encoded))
	if decoded != string(payload) {
		t.Fatalf("UTF-16 round trip = %q, want %q", decoded, payload)
	}
	if len(encoded) <= len([]rune(string(payload))) {
		t.Fatal("supplementary-plane rune was not encoded as a surrogate pair")
	}
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

func TestParseDecodedTunnelTokenWipesOwnedJSONPayload(t *testing.T) {
	validPayload, err := base64.StdEncoding.Strict().DecodeString(encodedTestToken(t, nil))
	if err != nil {
		t.Fatal(err)
	}
	credentials, err := parseDecodedTunnelToken(validPayload)
	if err != nil {
		t.Fatal(err)
	}
	defer wipe(credentials.TunnelSecret)
	for index, value := range validPayload {
		if value != 0 {
			t.Fatalf("valid decoded token byte %d was not wiped", index)
		}
	}

	invalidPayload := []byte(`{"a":"account-secret","s":"not-base64"}`)
	if _, err := parseDecodedTunnelToken(invalidPayload); err == nil {
		t.Fatal("invalid decoded token was accepted")
	}
	for index, value := range invalidPayload {
		if value != 0 {
			t.Fatalf("invalid decoded token byte %d was not wiped", index)
		}
	}
}

func TestDecodeTunnelTokenJSONWipesPopulatedSecretOnUnknownField(t *testing.T) {
	payload := []byte(`{
        "a":"account-secret",
        "s":"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "unknown":true,
        "t":"d8d8fa75-d6cb-4615-a09b-187ae29908fa"
    }`)
	var token connection.TunnelToken
	if err := decodeTunnelTokenJSON(payload, &token); err == nil {
		t.Fatal("token JSON with unknown field was accepted")
	}
	if len(token.TunnelSecret) != 32 {
		t.Fatalf("test secret was not populated before decode failure: len=%d", len(token.TunnelSecret))
	}
	for index, value := range token.TunnelSecret {
		if value != 0 {
			t.Fatalf("decode-error tunnel secret byte %d was not wiped", index)
		}
	}
}

func TestValidateDecodedTunnelTokenWipesSecretOnIdentityFailure(t *testing.T) {
	for _, test := range []struct {
		name   string
		mutate func(*connection.TunnelToken)
	}{
		{
			name: "invalid endpoint",
			mutate: func(token *connection.TunnelToken) {
				token.Endpoint = "bad endpoint/secret"
			},
		},
		{
			name: "invalid account",
			mutate: func(token *connection.TunnelToken) {
				token.AccountTag = "bad account"
			},
		},
	} {
		t.Run(test.name, func(t *testing.T) {
			secret := []byte("0123456789abcdef0123456789abcdef")
			token := connection.TunnelToken{
				AccountTag:   "account-secret",
				TunnelSecret: secret,
				TunnelID:     uuid.MustParse("d8d8fa75-d6cb-4615-a09b-187ae29908fa"),
			}
			test.mutate(&token)
			if _, err := validateDecodedTunnelToken(&token); err == nil {
				t.Fatal("invalid decoded token was accepted")
			}
			for index, value := range secret {
				if value != 0 {
					t.Fatalf("validation-error tunnel secret byte %d was not wiped", index)
				}
			}
		})
	}
}

func TestValidateOwnedTunnelTokenPayloadWipesPartialDecodeError(t *testing.T) {
	validRaw := encodedTestToken(t, nil)
	partialPayload, decodeErr := base64.StdEncoding.Strict().DecodeString(validRaw[:len(validRaw)-2] + "!")
	if decodeErr == nil || len(partialPayload) == 0 {
		t.Fatalf("test setup did not produce partial decode: len=%d err=%v", len(partialPayload), decodeErr)
	}
	owned := partialPayload
	payload, err := validateOwnedTunnelTokenPayload(owned, decodeErr)
	if err == nil || payload != nil {
		t.Fatalf("partial decode returned payload %q with error %v", payload, err)
	}
	for index, value := range owned {
		if value != 0 {
			t.Fatalf("partial decoded token byte %d was not wiped", index)
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
	for index, value := range credentials.TunnelSecret {
		if value != 0 {
			t.Fatalf("normal-stop tunnel secret byte %d was not wiped", index)
		}
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
	var runnerSecretAlias []byte
	handle := startTokenTunnel(
		token,
		"http://127.0.0.1:3000",
		recorder.record,
		func(_ context.Context, _ string, tunnel quickTunnel, _ tunnelEventObserver) error {
			runnerSecretAlias = tunnel.Credentials.TunnelSecret
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
	for index, value := range runnerSecretAlias {
		if value != 0 {
			t.Fatalf("runner-error tunnel secret byte %d was not wiped", index)
		}
	}
}

func TestTokenHandleNilRunnerTerminatesWithRedactedFailure(t *testing.T) {
	token := encodedTestToken(t, nil)
	handle := startTokenTunnel(token, "http://127.0.0.1:3000", nil, nil)
	handle.wait()
	snapshot := handle.snapshot()
	if snapshot.Status != statusFailed || !strings.Contains(snapshot.Error, "unavailable") {
		t.Fatalf("nil-runner snapshot = %#v", snapshot)
	}
	for _, secret := range []string{"account-secret", "0123456789abcdef0123456789abcdef", token} {
		if strings.Contains(snapshot.Error, secret) {
			t.Fatalf("nil-runner failure leaked %q: %q", secret, snapshot.Error)
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

type observedReadCloser struct {
	io.Reader
	closed *atomic.Bool
}

func (r *observedReadCloser) Close() error {
	r.closed.Store(true)
	return nil
}

func receiveBridgeEvent(t *testing.T, events <-chan bridgeEvent) bridgeEvent {
	t.Helper()
	select {
	case event := <-events:
		return event
	case <-time.After(time.Second):
		t.Fatal("callback event was not delivered")
		return bridgeEvent{}
	}
}
