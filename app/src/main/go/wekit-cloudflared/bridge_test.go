package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"sync"
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
	recorder := newEventRecorder()
	handle := startUnsupportedTokenTunnel("run-token-must-stay-secret", "http://127.0.0.1:8080", recorder.record)
	handle.wait()

	if got := handle.beginLogin(recorder.record); got != resultUnsupported {
		t.Fatalf("beginLogin result = %d, want unsupported", got)
	}
	if got := handle.selectExisting("tunnel-id", "public.example.com"); got != resultUnsupported {
		t.Fatalf("selectExisting result = %d, want unsupported", got)
	}
	snapshot := handle.snapshot()
	if snapshot.Status != statusUnsupported || strings.Contains(snapshot.Error, "run-token-must-stay-secret") {
		t.Fatalf("unsupported snapshot leaked token or wrong status: %#v", snapshot)
	}
}
