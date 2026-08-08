package main

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/fortytw2/leaktest"
)

func TestRealQuickTunnelForwardsAndStopsWithoutLeaking(t *testing.T) {
	if os.Getenv("WEKIT_CLOUDFLARED_INTEGRATION") != "1" {
		t.Skip("set WEKIT_CLOUDFLARED_INTEGRATION=1 to use the real trycloudflare.com service")
	}
	defer leaktest.CheckTimeout(t, 20*time.Second)()
	for attempt := range 2 {
		t.Run(fmt.Sprintf("session-%d", attempt+1), testRealQuickTunnelSession)
	}
}

func testRealQuickTunnelSession(t *testing.T) {
	origin := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/wekit-cloudflared-proof" {
			http.NotFound(response, request)
			return
		}
		response.Header().Set("Content-Type", "text/plain")
		_, _ = io.WriteString(response, "wekit-cloudflared-forwarded")
	}))
	defer origin.Close()

	recorder := newEventRecorder()
	handle := startQuickTunnel(origin.URL, recorder.record, requestQuickTunnel, runUpstreamTunnel)
	select {
	case <-recorder.ready:
	case <-time.After(90 * time.Second):
		handle.stop()
		t.Fatalf("timed out waiting for connection; status = %#v", handle.snapshot())
	}

	publicURL := handle.snapshot().URL
	if !strings.HasPrefix(publicURL, "https://") || !strings.HasSuffix(strings.TrimPrefix(publicURL, "https://"), ".trycloudflare.com") {
		handle.stop()
		t.Fatalf("public URL = %q, want https://*.trycloudflare.com", publicURL)
	}

	publicResolver := &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, _, _ string) (net.Conn, error) {
			return (&net.Dialer{}).DialContext(ctx, "udp", "1.1.1.1:53")
		},
	}
	transport := &http.Transport{
		DialContext: (&net.Dialer{Resolver: publicResolver}).DialContext,
	}
	client := &http.Client{Transport: transport, Timeout: 10 * time.Second}
	defer transport.CloseIdleConnections()
	// Quick Tunnel DNS is eventually published. The first feasibility run observed
	// the record appearing just after 90 seconds, so poll on readiness rather than
	// treating that propagation delay as a connector failure.
	deadline := time.Now().Add(3 * time.Minute)
	var lastErr error
	for time.Now().Before(deadline) {
		response, err := client.Get(publicURL + "/wekit-cloudflared-proof")
		if err == nil {
			body, readErr := io.ReadAll(io.LimitReader(response.Body, 1024))
			_ = response.Body.Close()
			if readErr == nil && response.StatusCode == http.StatusOK && string(body) == "wekit-cloudflared-forwarded" {
				lastErr = nil
				break
			}
			lastErr = &unexpectedPublicResponse{status: response.StatusCode, body: string(body), readErr: readErr}
		} else {
			lastErr = err
		}
		time.Sleep(time.Second)
	}
	if lastErr != nil {
		handle.stop()
		t.Fatalf("public forward never became ready: %v", lastErr)
	}

	if got := handle.stop(); got != resultOK {
		t.Fatalf("stop result = %d, want %d", got, resultOK)
	}
	events := recorder.snapshot()
	if events[len(events)-1].Status != statusStopped {
		t.Fatalf("last callback = %#v, want stopped", events[len(events)-1])
	}
}

func TestOwnedUpstreamObserverStopsItsDispatcherAndCanRepeat(t *testing.T) {
	defer leaktest.CheckTimeout(t, 5*time.Second)()

	for range 2 {
		events := make(chan bridgeEvent, 1)
		owned := newOwnedUpstreamObserver(observerFunc{
			onReconnecting: func() {
				events <- bridgeEvent{Status: statusReconnecting}
			},
		})
		deadline := time.Now().Add(time.Second)
		for {
			owned.observer.SendReconnect(0)
			select {
			case event := <-events:
				if event.Status != statusReconnecting {
					t.Fatalf("observer event = %#v", event)
				}
				owned.stop()
				goto stopped
			case <-time.After(time.Millisecond):
				if time.Now().After(deadline) {
					t.Fatal("owned observer did not dispatch an event")
				}
			}
		}
	stopped:
	}
}

type observerFunc struct {
	onReconnecting func()
}

func (o observerFunc) connected(string) {}
func (o observerFunc) reconnecting() {
	o.onReconnecting()
}
func (o observerFunc) disconnected() {}

type unexpectedPublicResponse struct {
	status  int
	body    string
	readErr error
}

func (e *unexpectedPublicResponse) Error() string {
	if e.readErr != nil {
		return e.readErr.Error()
	}
	return http.StatusText(e.status) + ": " + e.body
}
