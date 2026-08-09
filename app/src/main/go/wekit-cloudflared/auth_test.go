package main

import (
	"context"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) Do(request *http.Request) (*http.Response, error) {
	return f(request)
}

func response(status int, body string) *http.Response {
	return &http.Response{
		StatusCode: status,
		Header:     make(http.Header),
		Body:       io.NopCloser(strings.NewReader(body)),
	}
}

func originCertificate(t *testing.T, accountID, apiToken string) []byte {
	t.Helper()
	payload, err := json.Marshal(map[string]string{
		"zoneID":    "zone-id",
		"accountID": accountID,
		"apiToken":  apiToken,
	})
	if err != nil {
		t.Fatal(err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "ARGO TUNNEL TOKEN", Bytes: payload})
}

func TestLoginTransferReturnsURLBeforePollingAndNeverOpensBrowser(t *testing.T) {
	pollEntered := make(chan struct{})
	releasePoll := make(chan struct{})
	client := roundTripFunc(func(request *http.Request) (*http.Response, error) {
		if request.Method != http.MethodGet {
			t.Fatalf("method = %s, want GET", request.Method)
		}
		close(pollEntered)
		select {
		case <-releasePoll:
			return response(http.StatusOK, string(originCertificate(t, "account-id", "api-secret"))), nil
		case <-request.Context().Done():
			return nil, request.Context().Err()
		}
	})

	transfer, err := newLoginTransfer(client, bytesReader32(7))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(
		transfer.authorizationURL,
		"https://dash.cloudflare.com/argotunnel?callback=https%3A%2F%2Flogin.cloudflareaccess.org%2F",
	) {
		t.Fatalf("authorization URL = %q", transfer.authorizationURL)
	}

	session := beginAuthSession(11, transfer, defaultAuthAPIFactory(client))
	defer session.close()
	if snapshot := session.snapshot(); snapshot.AuthorizationURL != transfer.authorizationURL {
		t.Fatalf("snapshot URL = %q", snapshot.AuthorizationURL)
	}
	select {
	case <-pollEntered:
	case <-time.After(time.Second):
		t.Fatal("poll did not start asynchronously")
	}
	close(releasePoll)
	waitForAuthState(t, session, authAuthorized)
}

func TestLoginCancellationQuiescesPollAndCannotPublishAuthorized(t *testing.T) {
	pollExited := make(chan struct{})
	client := roundTripFunc(func(request *http.Request) (*http.Response, error) {
		<-request.Context().Done()
		close(pollExited)
		return nil, request.Context().Err()
	})
	transfer, err := newLoginTransfer(client, bytesReader32(3))
	if err != nil {
		t.Fatal(err)
	}
	session := beginAuthSession(12, transfer, defaultAuthAPIFactory(client))
	session.close()
	select {
	case <-pollExited:
	default:
		t.Fatal("close returned before poll exited")
	}
	if got := session.snapshot().State; got != authStopped {
		t.Fatalf("state = %v, want stopped", got)
	}
}

func TestLoginRejectsOversizedAndMalformedOriginCertificateWithoutLeakingIt(t *testing.T) {
	secrets := []string{"api-secret-value", strings.Repeat("x", maxOriginCertificateBytes+1)}
	certificates := [][]byte{
		originCertificate(t, "account-id", secrets[0])[:12],
		[]byte(secrets[1]),
	}
	for index, certificate := range certificates {
		client := roundTripFunc(func(*http.Request) (*http.Response, error) {
			return response(http.StatusOK, string(certificate)), nil
		})
		transfer, err := newLoginTransfer(client, bytesReader32(byte(index+1)))
		if err != nil {
			t.Fatal(err)
		}
		session := beginAuthSession(uint64(20+index), transfer, defaultAuthAPIFactory(client))
		waitForAuthState(t, session, authFailed)
		errorText := session.snapshot().Error
		for _, secret := range secrets {
			if strings.Contains(errorText, secret) {
				t.Fatalf("failure leaked secret: %q", errorText)
			}
		}
		session.close()
	}
}

func TestReadOnlyAPIListsRemoteTunnelsWithValidatedIngressEvidence(t *testing.T) {
	var methods []string
	client := roundTripFunc(func(request *http.Request) (*http.Response, error) {
		methods = append(methods, request.Method)
		if request.Header.Get("Authorization") != "Bearer api-secret" {
			t.Fatalf("authorization header missing")
		}
		switch request.URL.Path {
		case "/client/v4/accounts/account-id/cfd_tunnel":
			return response(http.StatusOK, `{
                    "success":true,
                    "result":[{"id":"d8d8fa75-d6cb-4615-a09b-187ae29908fa","name":"receipts","deleted_at":null,"remote_config":true,"config_src":"cloudflare"}],
                    "result_info":{"page":1,"per_page":100,"count":1,"total_count":1}
                }`), nil
		case "/client/v4/accounts/account-id/cfd_tunnel/d8d8fa75-d6cb-4615-a09b-187ae29908fa/configurations":
			return response(http.StatusOK, `{
                    "success":true,
                    "result":{"config":{"ingress":[
                        {"hostname":"Receipts.Example.COM","service":"http://localhost:3000"},
                        {"service":"http_status:404"}
                    ]}}
                }`), nil
		default:
			t.Fatalf("unexpected path %s", request.URL.Path)
			return nil, errors.New("unreachable")
		}
	})
	credential := newTestAuthCredential("account-id", "api-secret")
	defer credential.clear()
	api := newReadOnlyTunnelAPI(client, "https://api.cloudflare.com/client/v4", credential)
	tunnels, err := api.listExisting(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(tunnels) != 1 || tunnels[0].ID != "d8d8fa75-d6cb-4615-a09b-187ae29908fa" ||
		tunnels[0].Name != "receipts" {
		t.Fatalf("tunnels = %#v", tunnels)
	}
	wantIngress := []configuredIngress{{Hostname: "receipts.example.com", Service: "http://localhost:3000"}}
	if !equalIngress(tunnels[0].Ingress, wantIngress) {
		t.Fatalf("ingress = %#v, want %#v", tunnels[0].Ingress, wantIngress)
	}
	for _, method := range methods {
		if method != http.MethodGet {
			t.Fatalf("observed mutating method %s", method)
		}
	}
}

func TestReadOnlyAPIRejectsDuplicateHostnamesAndOversizedPagination(t *testing.T) {
	tunnelEnvelope := `{
        "success":true,
        "result":[{"id":"d8d8fa75-d6cb-4615-a09b-187ae29908fa","name":"receipts","deleted_at":null,"remote_config":true}],
        "result_info":{"page":1,"per_page":100,"count":1,"total_count":1}
    }`
	tests := []struct {
		name   string
		list   string
		config string
	}{
		{
			name: "duplicate hostname",
			list: tunnelEnvelope,
			config: `{"success":true,"result":{"config":{"ingress":[
                {"hostname":"a.example.com","service":"http://localhost:3000"},
                {"hostname":"A.EXAMPLE.COM","service":"http://localhost:3000"}
            ]}}}`,
		},
		{
			name: "oversized total",
			list: strings.Replace(tunnelEnvelope, `"total_count":1`, `"total_count":101`, 1),
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			client := roundTripFunc(func(request *http.Request) (*http.Response, error) {
				if strings.HasSuffix(request.URL.Path, "/configurations") {
					return response(http.StatusOK, test.config), nil
				}
				return response(http.StatusOK, test.list), nil
			})
			credential := newTestAuthCredential("account-id", "api-secret")
			defer credential.clear()
			_, err := newReadOnlyTunnelAPI(client, "https://api.cloudflare.com/client/v4", credential).
				listExisting(context.Background())
			if err == nil {
				t.Fatal("listExisting succeeded")
			}
		})
	}
}

func TestReadOnlyAPIRejectsNonSuccessAndRedactsTokenResponses(t *testing.T) {
	apiSecret := "account-api-secret"
	tunnelSecret := "selected-run-token"
	client := roundTripFunc(func(request *http.Request) (*http.Response, error) {
		return response(http.StatusForbidden, `{"success":false,"errors":[{"code":1000,"message":"`+apiSecret+` `+tunnelSecret+`"}]}`), nil
	})
	credential := newTestAuthCredential("account-id", apiSecret)
	defer credential.clear()
	api := newReadOnlyTunnelAPI(client, "https://api.cloudflare.com/client/v4", credential)
	_, err := api.getTunnelToken(
		context.Background(),
		"d8d8fa75-d6cb-4615-a09b-187ae29908fa",
	)
	if err == nil {
		t.Fatal("token request succeeded")
	}
	for _, secret := range []string{apiSecret, tunnelSecret} {
		if strings.Contains(err.Error(), secret) {
			t.Fatalf("error leaked %q: %q", secret, err)
		}
	}
}

func TestAuthSessionReplacementAndSelectionKeepTokenOutOfSnapshot(t *testing.T) {
	selectedRunToken := encodedTestToken(t, nil)
	firstRelease := make(chan struct{})
	firstExited := make(chan struct{})
	firstTransfer := fakeLoginTransfer{
		authorizationURL: "https://dash.cloudflare.com/first",
		poll: func(ctx context.Context) ([]byte, error) {
			select {
			case <-firstRelease:
				return originCertificate(t, "old-account", "old-secret"), nil
			case <-ctx.Done():
				close(firstExited)
				return nil, ctx.Err()
			}
		},
	}
	secondTransfer := fakeLoginTransfer{
		authorizationURL: "https://dash.cloudflare.com/second",
		poll: func(context.Context) ([]byte, error) {
			return originCertificate(t, "account-id", "api-secret"), nil
		},
	}
	client := roundTripFunc(func(request *http.Request) (*http.Response, error) {
		if strings.HasSuffix(request.URL.Path, "/token") {
			return response(http.StatusOK, `{"success":true,"result":"`+selectedRunToken+`"}`), nil
		}
		return response(http.StatusOK, `{
            "success":true,
            "result":[{"id":"d8d8fa75-d6cb-4615-a09b-187ae29908fa","name":"receipts","deleted_at":null,"remote_config":false}],
            "result_info":{"page":1,"per_page":100,"count":1,"total_count":1}
        }`), nil
	})
	manager := newAuthSessionManager(defaultAuthAPIFactory(client))
	manager.replace(31, firstTransfer)
	manager.replace(32, secondTransfer)
	select {
	case <-firstExited:
	case <-time.After(time.Second):
		t.Fatal("replacement did not quiesce prior poll")
	}
	session := manager.current()
	waitForAuthState(t, session, authAuthorized)
	token, err := session.selectToken(
		context.Background(),
		"d8d8fa75-d6cb-4615-a09b-187ae29908fa",
		"https://manual.example.com",
	)
	if err != nil {
		t.Fatal(err)
	}
	if token != selectedRunToken {
		t.Fatalf("token = %q", token)
	}
	payload, err := json.Marshal(session.snapshot())
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(payload), token) || strings.Contains(string(payload), "api-secret") {
		t.Fatalf("snapshot leaked secret: %s", payload)
	}
	manager.close()
	close(firstRelease)
}

func TestAuthCloseCancelsAndDrainsInFlightAPIWork(t *testing.T) {
	apiEntered := make(chan struct{})
	apiExited := make(chan struct{})
	client := roundTripFunc(func(request *http.Request) (*http.Response, error) {
		if strings.Contains(request.URL.Host, "login.cloudflareaccess.org") {
			return response(http.StatusOK, string(originCertificate(t, "account-id", "api-secret"))), nil
		}
		close(apiEntered)
		<-request.Context().Done()
		close(apiExited)
		return nil, request.Context().Err()
	})
	transfer, err := newLoginTransfer(client, bytesReader32(9))
	if err != nil {
		t.Fatal(err)
	}
	session := beginAuthSession(40, transfer, defaultAuthAPIFactory(client))
	waitForAuthState(t, session, authAuthorized)
	listReturned := make(chan struct{})
	go func() {
		_, _ = session.list(context.Background())
		close(listReturned)
	}()
	<-apiEntered
	session.close()
	select {
	case <-apiExited:
	default:
		t.Fatal("close returned before API transport exited")
	}
	select {
	case <-listReturned:
	case <-time.After(time.Second):
		t.Fatal("cancelled API operation did not return")
	}
}

func bytesReader32(value byte) io.Reader {
	return strings.NewReader(strings.Repeat(string([]byte{value}), 32))
}

func waitForAuthState(t *testing.T, session *authSession, state authState) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		if session.snapshot().State == state {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("state = %v, want %v", session.snapshot().State, state)
}

func newTestAuthCredential(accountID, apiToken string) *authCredential {
	return &authCredential{accountID: []byte(accountID), apiToken: []byte(apiToken)}
}

func equalIngress(left, right []configuredIngress) bool {
	if len(left) != len(right) {
		return false
	}
	for index := range left {
		if left[index] != right[index] {
			return false
		}
	}
	return true
}

type fakeLoginTransfer struct {
	authorizationURL string
	poll             func(context.Context) ([]byte, error)
}

func (t fakeLoginTransfer) authorization() string { return t.authorizationURL }

func (t fakeLoginTransfer) wait(ctx context.Context) ([]byte, error) { return t.poll(ctx) }
