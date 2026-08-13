package main

import (
	"bytes"
	"context"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/cloudflare/cloudflared/connection"
	"github.com/google/uuid"
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
	return originCertificateWithEndpoint(t, accountID, apiToken, "")
}

func originCertificateWithEndpoint(t *testing.T, accountID, apiToken, endpoint string) []byte {
	t.Helper()
	payload, err := json.Marshal(map[string]string{
		"zoneID":    "zone-id",
		"accountID": accountID,
		"apiToken":  apiToken,
		"endpoint":  endpoint,
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

func TestLoginPollContinuesAfterEmptyOKResponses(t *testing.T) {
	wantCertificate := originCertificate(t, "abcdabcdabcdabcd1234567890abcdef", "api-secret")
	calls := 0
	client := roundTripFunc(func(*http.Request) (*http.Response, error) {
		calls++
		if calls == 1 {
			return response(http.StatusOK, ""), nil
		}
		return response(http.StatusOK, string(wantCertificate)), nil
	})
	transfer, err := newLoginTransfer(client, bytesReader32(4))
	if err != nil {
		t.Fatal(err)
	}
	certificate, err := transfer.wait(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	defer wipe(certificate)
	if calls != 2 || !bytes.Equal(certificate, wantCertificate) {
		t.Fatalf("calls = %d, certificate matched = %v", calls, bytes.Equal(certificate, wantCertificate))
	}
}

func TestLoginPollTimesOutAfterTenEmptyOKResponses(t *testing.T) {
	calls := 0
	client := roundTripFunc(func(*http.Request) (*http.Response, error) {
		calls++
		return response(http.StatusOK, ""), nil
	})
	transfer, err := newLoginTransfer(client, bytesReader32(5))
	if err != nil {
		t.Fatal(err)
	}
	certificate, err := transfer.wait(context.Background())
	if err == nil || certificate != nil {
		t.Fatalf("wait returned certificate %q with error %v", certificate, err)
	}
	if calls != loginPollAttempts {
		t.Fatalf("poll calls = %d, want %d", calls, loginPollAttempts)
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

func TestDecodeAuthCredentialAcceptsRealAccountTagAndRejectsPathEscapes(t *testing.T) {
	validAccountID := "abcdabcdabcdabcd1234567890abcdef"
	credential, err := decodeAuthCredential(originCertificate(t, validAccountID, "api-secret"))
	if err != nil {
		t.Fatalf("real account tag was rejected: %v", err)
	}
	if credential.account() != validAccountID {
		t.Fatalf("account ID = %q", credential.account())
	}
	credential.clear()

	for _, accountID := range []string{
		"../account",
		"account/child",
		"account?query",
		strings.Repeat("a", 33),
	} {
		t.Run(accountID, func(t *testing.T) {
			credential, err := decodeAuthCredential(originCertificate(t, accountID, "api-secret"))
			if credential != nil {
				credential.clear()
			}
			if err == nil {
				t.Fatalf("unsafe account ID %q was accepted", accountID)
			}
		})
	}
}

func TestDecodeAuthCredentialRequiresExactlyOneModernPEMBlock(t *testing.T) {
	certificate := originCertificate(t, "abcdabcdabcdabcd1234567890abcdef", "api-secret")
	legacy := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: []byte("legacy")})
	tests := map[string][]byte{
		"trailing garbage": append(append([]byte(nil), certificate...), []byte("not-pem")...),
		"legacy block":     append(append([]byte(nil), legacy...), certificate...),
		"second token":     append(append([]byte(nil), certificate...), certificate...),
	}
	for name, input := range tests {
		t.Run(name, func(t *testing.T) {
			credential, err := decodeAuthCredential(input)
			if credential != nil {
				credential.clear()
			}
			if err == nil {
				t.Fatal("non-unique modern origin certificate was accepted")
			}
		})
	}
}

func TestConsumeStrictOriginCertificateWipesDecodedPEMPayload(t *testing.T) {
	certificate := originCertificate(t, "abcdabcdabcdabcd1234567890abcdef", "api-secret")
	for _, test := range []struct {
		name        string
		consumerErr error
	}{
		{name: "success"},
		{name: "consumer failure", consumerErr: errors.New("injected consumer failure")},
	} {
		t.Run(test.name, func(t *testing.T) {
			var decodedPayload []byte
			err := consumeStrictOriginCertificate(certificate, func(payload []byte) error {
				decodedPayload = payload
				if !bytes.Contains(payload, []byte("api-secret")) {
					t.Fatalf("decoded payload did not contain test secret: %q", payload)
				}
				return test.consumerErr
			})
			if !errors.Is(err, test.consumerErr) {
				t.Fatalf("error = %v, want %v", err, test.consumerErr)
			}
			if len(decodedPayload) == 0 {
				t.Fatal("consumer did not receive decoded PEM payload")
			}
			for index, value := range decodedPayload {
				if value != 0 {
					t.Fatalf("decoded PEM byte %d was not wiped: %q", index, decodedPayload)
				}
			}
		})
	}
}

func TestConsumeDecodedOriginCertificateBlockWipesPayloadOnStructuralFailure(t *testing.T) {
	for _, test := range []struct {
		name      string
		blockType string
		rest      []byte
	}{
		{name: "wrong block type", blockType: "PRIVATE KEY"},
		{name: "trailing or second block", blockType: "ARGO TUNNEL TOKEN", rest: []byte("trailing")},
	} {
		t.Run(test.name, func(t *testing.T) {
			decodedPayload := []byte(`{"zoneID":"zone-id","apiToken":"api-secret"}`)
			block := &pem.Block{Type: test.blockType, Bytes: decodedPayload}
			consumerCalled := false
			err := consumeDecodedOriginCertificateBlock(block, test.rest, func([]byte) error {
				consumerCalled = true
				return nil
			})
			if err == nil {
				t.Fatal("structurally invalid PEM block was accepted")
			}
			if consumerCalled {
				t.Fatal("consumer was called for structurally invalid PEM block")
			}
			for index, value := range decodedPayload {
				if value != 0 {
					t.Fatalf("decoded PEM byte %d was not wiped: %q", index, decodedPayload)
				}
			}
		})
	}
}

func TestDecodeAuthCredentialPreservesOriginCertJSONSemantics(t *testing.T) {
	validAccountID := "abcdabcdabcdabcd1234567890abcdef"
	t.Run("malformed JSON", func(t *testing.T) {
		certificate := pem.EncodeToMemory(&pem.Block{
			Type:  "ARGO TUNNEL TOKEN",
			Bytes: []byte(`{"zoneID":"zone-id","apiToken":`),
		})
		if credential, err := decodeAuthCredential(certificate); err == nil || credential != nil {
			t.Fatal("malformed origin certificate JSON was accepted")
		}
	})
	t.Run("missing zone", func(t *testing.T) {
		payload, err := json.Marshal(map[string]string{
			"accountID": validAccountID,
			"apiToken":  "api-secret",
		})
		if err != nil {
			t.Fatal(err)
		}
		certificate := pem.EncodeToMemory(&pem.Block{Type: "ARGO TUNNEL TOKEN", Bytes: payload})
		if credential, err := decodeAuthCredential(certificate); err == nil || credential != nil {
			t.Fatal("origin certificate without zoneID was accepted")
		}
	})
	t.Run("endpoint lowercase", func(t *testing.T) {
		credential, err := decodeAuthCredential(
			originCertificateWithEndpoint(t, validAccountID, "api-secret", "FED"),
		)
		if err != nil {
			t.Fatal(err)
		}
		defer credential.clear()
		if string(credential.endpoint) != "fed" {
			t.Fatalf("endpoint = %q, want fed", credential.endpoint)
		}
	})
}

func TestAuthHTTPClientRejectsRedirectsBeforeForwardingAuthorization(t *testing.T) {
	targetHit := make(chan string, 1)
	target := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		targetHit <- request.Header.Get("Authorization")
		writer.WriteHeader(http.StatusOK)
	}))
	defer target.Close()
	source := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
		http.Redirect(writer, &http.Request{}, target.URL, http.StatusFound)
	}))
	defer source.Close()

	client := newAuthHTTPClient()
	client.Transport = http.DefaultTransport
	request, err := http.NewRequest(http.MethodGet, source.URL, nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer account-secret")
	response, err := client.Do(request)
	if response != nil {
		response.Body.Close()
	}
	if err == nil {
		t.Fatal("redirect was followed")
	}
	select {
	case authorization := <-targetHit:
		t.Fatalf("redirect target received Authorization %q", authorization)
	default:
	}
}

func TestReadBoundedBodyWipesPartialPayloadOnReadError(t *testing.T) {
	reader := &partialErrorReader{payload: []byte("partial-secret")}
	payload, err := readBoundedBody(reader, maxAPIResponseBytes)
	if err == nil || payload != nil {
		t.Fatalf("read returned payload %q with error %v", payload, err)
	}
	if len(reader.exposed) == 0 {
		t.Fatal("reader did not expose the destination buffer")
	}
	for index, value := range reader.exposed {
		if value != 0 {
			t.Fatalf("partial payload byte %d was not wiped: %q", index, reader.exposed)
		}
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

func TestIndependentAuthListJSONOmitsInternalServiceEvidence(t *testing.T) {
	client := roundTripFunc(func(request *http.Request) (*http.Response, error) {
		switch {
		case strings.Contains(request.URL.Host, "login.cloudflareaccess.org"):
			return response(http.StatusOK, string(originCertificate(t, "account-id", "api-secret"))), nil
		case strings.HasSuffix(request.URL.Path, "/configurations"):
			return response(http.StatusOK, `{
                    "success":true,
                    "result":{"config":{"ingress":[
                        {"hostname":"Receipts.Example.COM","service":"http://user:password@localhost:3000/path?token=service-secret"},
                        {"service":"http_status:404"}
                    ]}}
                }`), nil
		default:
			return response(http.StatusOK, `{
                    "success":true,
                    "result":[{"id":"d8d8fa75-d6cb-4615-a09b-187ae29908fa","name":"receipts","deleted_at":null,"remote_config":true,"config_src":"cloudflare"}],
                    "result_info":{"page":1,"per_page":100,"count":1,"total_count":1}
                }`), nil
		}
	})
	transfer, err := newLoginTransfer(client, bytesReader32(15))
	if err != nil {
		t.Fatal(err)
	}
	handle := newIndependentAuthHandle(transfer, defaultAuthAPIFactory(client), nil)
	defer handle.close()
	waitForAuthState(t, handle.session, authAuthorized)
	payload, err := handle.listJSON(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	for _, forbidden := range []string{"service", "user", "password", "service-secret"} {
		if strings.Contains(string(payload), forbidden) {
			t.Fatalf("public tunnel JSON leaked %q: %s", forbidden, payload)
		}
	}
	var decoded struct {
		Generation uint64 `json:"generation"`
		Tunnels    []struct {
			ID        string   `json:"id"`
			Name      string   `json:"name"`
			Hostnames []string `json:"hostnames"`
		} `json:"tunnels"`
	}
	if err := json.Unmarshal(payload, &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded.Generation != handle.generation || len(decoded.Tunnels) != 1 {
		t.Fatalf("public tunnel list = %#v", decoded)
	}
	tunnel := decoded.Tunnels[0]
	if tunnel.ID != "d8d8fa75-d6cb-4615-a09b-187ae29908fa" || tunnel.Name != "receipts" ||
		len(tunnel.Hostnames) != 1 || tunnel.Hostnames[0] != "receipts.example.com" {
		t.Fatalf("public tunnel = %#v", tunnel)
	}
	if len(payload) > maxAuthJSONBytes {
		t.Fatalf("public tunnel JSON length = %d", len(payload))
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

func TestReadOnlyAPIRejectsTunnelTokensForAnotherTunnelOrAccount(t *testing.T) {
	requestedTunnelID := uuid.MustParse("d8d8fa75-d6cb-4615-a09b-187ae29908fa")
	tests := []struct {
		name   string
		mutate func(*connection.TunnelToken)
	}{
		{
			name: "tunnel mismatch",
			mutate: func(token *connection.TunnelToken) {
				token.TunnelID = uuid.MustParse("9ab45016-f95d-4d41-b4a6-3c7495ec0f42")
			},
		},
		{
			name: "account mismatch",
			mutate: func(token *connection.TunnelToken) {
				token.AccountTag = "another-account"
			},
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			rawToken := encodedTestToken(t, test.mutate)
			client := roundTripFunc(func(*http.Request) (*http.Response, error) {
				return response(http.StatusOK, `{"success":true,"result":"`+rawToken+`"}`), nil
			})
			credential := newTestAuthCredential("account-secret", "api-secret")
			defer credential.clear()
			_, err := newReadOnlyTunnelAPI(client, cloudflareAPIURL, credential).
				getTunnelToken(context.Background(), requestedTunnelID.String())
			if err == nil {
				t.Fatal("mismatched tunnel token was accepted")
			}
			for _, secret := range []string{rawToken, "another-account", "api-secret"} {
				if strings.Contains(err.Error(), secret) {
					t.Fatalf("error leaked %q: %q", secret, err)
				}
			}
		})
	}
}

func TestAuthSessionReplacementAndSelectionKeepTokenOutOfSnapshot(t *testing.T) {
	selectedRunToken := encodedTestToken(t, func(token *connection.TunnelToken) {
		token.AccountTag = "account-id"
	})
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

func TestAuthSessionManagerConcurrentOlderReplaceCannotOverwriteNewer(t *testing.T) {
	initialEntered := make(chan struct{})
	initialCancelled := make(chan struct{})
	releaseInitial := make(chan struct{})
	manager := newAuthSessionManager(defaultAuthAPIFactory(roundTripFunc(func(*http.Request) (*http.Response, error) {
		return nil, errors.New("unexpected API request")
	})))
	manager.replace(1, fakeLoginTransfer{
		authorizationURL: "https://dash.cloudflare.com/initial",
		poll: func(ctx context.Context) ([]byte, error) {
			close(initialEntered)
			<-ctx.Done()
			close(initialCancelled)
			<-releaseInitial
			return nil, ctx.Err()
		},
	})
	<-initialEntered

	olderReturned := make(chan *authSession, 1)
	go func() {
		olderReturned <- manager.replace(2, fakeLoginTransfer{
			authorizationURL: "https://dash.cloudflare.com/older",
			poll: func(context.Context) ([]byte, error) {
				return originCertificate(t, "abcdabcdabcdabcd1234567890abcdef", "older-secret"), nil
			},
		})
	}()
	<-initialCancelled

	newerBegan := make(chan struct{})
	allowNewerBegin := make(chan struct{})
	newerReturned := make(chan *authSession, 1)
	go func() {
		newerReturned <- manager.replace(3, observedLoginTransfer{
			authorize: func() string {
				close(newerBegan)
				<-allowNewerBegin
				return "https://dash.cloudflare.com/newer"
			},
			poll: func(context.Context) ([]byte, error) {
				return originCertificate(t, "abcdabcdabcdabcd1234567890abcdef", "newer-secret"), nil
			},
		})
	}()

	select {
	case <-newerBegan:
		close(allowNewerBegin)
	case <-time.After(100 * time.Millisecond):
		close(allowNewerBegin)
	}
	close(releaseInitial)
	older := receiveSession(t, olderReturned)
	newer := receiveSession(t, newerReturned)
	current := manager.current()
	if current == nil || current.generation != 3 {
		if current == nil {
			t.Fatal("manager has no current session")
		}
		t.Fatalf("current generation = %d, want 3", current.generation)
	}
	if newer == nil {
		t.Fatal("newer replacement was rejected")
	}
	if older != nil && older.snapshot().State != authStopped {
		t.Fatalf("older state = %v, want stopped", older.snapshot().State)
	}
	manager.close()
}

func TestAuthSessionManagerRejectsLowerGenerationWithoutStartingTransfer(t *testing.T) {
	manager := newAuthSessionManager(defaultAuthAPIFactory(roundTripFunc(func(*http.Request) (*http.Response, error) {
		return nil, errors.New("unexpected API request")
	})))
	higher := manager.replace(8, fakeLoginTransfer{
		authorizationURL: "https://dash.cloudflare.com/higher",
		poll: func(ctx context.Context) ([]byte, error) {
			<-ctx.Done()
			return nil, ctx.Err()
		},
	})
	if higher == nil {
		t.Fatal("higher generation was rejected")
	}
	lowerStarted := make(chan struct{}, 1)
	lower := manager.replace(7, observedLoginTransfer{
		authorize: func() string {
			lowerStarted <- struct{}{}
			return "https://dash.cloudflare.com/lower"
		},
		poll: func(context.Context) ([]byte, error) {
			return nil, errors.New("lower transfer unexpectedly polled")
		},
	})
	if lower != nil {
		t.Fatal("lower generation was published")
	}
	select {
	case <-lowerStarted:
		t.Fatal("rejected transfer was started")
	default:
	}
	if current := manager.current(); current != higher || current.generation != 8 {
		t.Fatal("lower generation replaced current session")
	}
	manager.close()
}

func TestAuthSessionManagerCloseCannotRaceWithReplacementPublication(t *testing.T) {
	initialEntered := make(chan struct{})
	initialCancelled := make(chan struct{})
	releaseInitial := make(chan struct{})
	manager := newAuthSessionManager(defaultAuthAPIFactory(roundTripFunc(func(*http.Request) (*http.Response, error) {
		return nil, errors.New("unexpected API request")
	})))
	manager.replace(1, fakeLoginTransfer{
		authorizationURL: "https://dash.cloudflare.com/initial",
		poll: func(ctx context.Context) ([]byte, error) {
			close(initialEntered)
			<-ctx.Done()
			close(initialCancelled)
			<-releaseInitial
			return nil, ctx.Err()
		},
	})
	<-initialEntered

	closeReturned := make(chan struct{})
	go func() {
		manager.close()
		close(closeReturned)
	}()
	<-initialCancelled

	replacementBegan := make(chan struct{})
	allowReplacementBegin := make(chan struct{})
	replacementReturned := make(chan *authSession, 1)
	go func() {
		replacementReturned <- manager.replace(2, observedLoginTransfer{
			authorize: func() string {
				close(replacementBegan)
				<-allowReplacementBegin
				return "https://dash.cloudflare.com/replacement"
			},
			poll: func(context.Context) ([]byte, error) {
				return originCertificate(t, "abcdabcdabcdabcd1234567890abcdef", "replacement-secret"), nil
			},
		})
	}()
	select {
	case <-replacementBegan:
		close(allowReplacementBegin)
	case <-time.After(100 * time.Millisecond):
		close(allowReplacementBegin)
	}
	close(releaseInitial)
	select {
	case <-closeReturned:
	case <-time.After(time.Second):
		t.Fatal("manager close did not return")
	}
	if replacement := receiveSession(t, replacementReturned); replacement != nil {
		t.Fatal("replacement was published after manager close")
	}
	if manager.current() != nil {
		t.Fatal("manager published a session after close returned")
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

type observedLoginTransfer struct {
	authorize func() string
	poll      func(context.Context) ([]byte, error)
}

func (t observedLoginTransfer) authorization() string { return t.authorize() }

func (t observedLoginTransfer) wait(ctx context.Context) ([]byte, error) { return t.poll(ctx) }

type partialErrorReader struct {
	payload []byte
	exposed []byte
	read    bool
}

func (r *partialErrorReader) Read(destination []byte) (int, error) {
	if r.read {
		return 0, io.EOF
	}
	r.read = true
	count := copy(destination, r.payload)
	r.exposed = destination[:count]
	return count, errors.New("injected read failure")
}

func receiveSession(t *testing.T, sessions <-chan *authSession) *authSession {
	t.Helper()
	select {
	case session := <-sessions:
		return session
	case <-time.After(time.Second):
		t.Fatal("manager operation did not return")
		return nil
	}
}
