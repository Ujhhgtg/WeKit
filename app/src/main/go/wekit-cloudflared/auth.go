package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"path"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/cloudflare/cloudflared/credentials"
	"github.com/google/uuid"
	"golang.org/x/crypto/nacl/box"
)

func newAuthHTTPClient() *http.Client {
	transport := &http.Transport{
		TLSHandshakeTimeout:   15 * time.Second,
		ResponseHeaderTimeout: 60 * time.Second,
	}
	return &http.Client{
		Transport: transport,
		Timeout:   60 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return errors.New("authentication redirects are disabled")
		},
	}
}

const (
	cloudflareLoginURL        = "https://dash.cloudflare.com/argotunnel"
	cloudflareLoginStoreURL   = "https://login.cloudflareaccess.org/"
	cloudflareAPIURL          = "https://api.cloudflare.com/client/v4"
	maxOriginCertificateBytes = 64 * 1024
	maxAPIResponseBytes       = 256 * 1024
	maxExistingTunnels        = 100
	maxConfiguredIngress      = 100
	maxTunnelNameBytes        = 128
	maxServiceBytes           = 2048
	loginPollAttempts         = 10
)

var (
	dnsLabelPattern      = regexp.MustCompile(`^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$`)
	authAccountIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,32}$`)
)

type httpDoer interface {
	Do(*http.Request) (*http.Response, error)
}

type preparedLoginTransfer interface {
	authorization() string
	wait(context.Context) ([]byte, error)
}

type loginTransfer struct {
	client           httpDoer
	publicKey        string
	authorizationURL string
	storeURL         string
}

func newLoginTransfer(client httpDoer, randomness io.Reader) (*loginTransfer, error) {
	if client == nil {
		return nil, errors.New("login transport is unavailable")
	}
	if randomness == nil {
		randomness = rand.Reader
	}
	publicKey, _, err := box.GenerateKey(randomness)
	if err != nil {
		return nil, errors.New("could not prepare browser login")
	}
	encodedKey := base64.URLEncoding.EncodeToString(publicKey[:])
	callback, err := url.Parse(cloudflareLoginStoreURL)
	if err != nil {
		return nil, errors.New("could not prepare browser login")
	}
	callback.Path = path.Join(callback.Path, encodedKey)
	login, err := url.Parse(cloudflareLoginURL)
	if err != nil {
		return nil, errors.New("could not prepare browser login")
	}
	query := login.Query()
	query.Set("callback", callback.String())
	login.RawQuery = query.Encode()
	if len(login.String()) > maxURLBytes || len(callback.String()) > maxURLBytes {
		return nil, errors.New("browser login URL is too long")
	}
	return &loginTransfer{
		client:           client,
		publicKey:        encodedKey,
		authorizationURL: login.String(),
		storeURL:         callback.String(),
	}, nil
}

func (t *loginTransfer) authorization() string { return t.authorizationURL }

func (t *loginTransfer) wait(ctx context.Context) ([]byte, error) {
	for attempt := 0; attempt < loginPollAttempts; attempt++ {
		request, err := http.NewRequestWithContext(ctx, http.MethodGet, t.storeURL, nil)
		if err != nil {
			return nil, errors.New("could not prepare login poll")
		}
		request.Header.Set("User-Agent", "WeKit cloudflared bridge/"+cloudflaredVersion)
		response, err := t.client.Do(request)
		if err != nil {
			if errors.Is(err, context.Canceled) || ctx.Err() != nil {
				return nil, context.Canceled
			}
			return nil, errors.New("browser login poll failed")
		}
		body, readErr := readBoundedBody(response.Body, maxOriginCertificateBytes)
		response.Body.Close()
		if readErr != nil {
			return nil, errors.New("browser login returned an oversized certificate")
		}
		if response.StatusCode == http.StatusOK {
			if len(body) == 0 {
				continue
			}
			return body, nil
		}
		wipe(body)
		if response.StatusCode >= http.StatusInternalServerError {
			return nil, fmt.Errorf("browser login service returned HTTP %d", response.StatusCode)
		}
	}
	return nil, errors.New("browser login timed out")
}

type authState string

const (
	authWaiting    authState = "WAITING"
	authAuthorized authState = "AUTHORIZED"
	authFailed     authState = "FAILED"
	authStopped    authState = "STOPPED"
)

type configuredIngress struct {
	Hostname string `json:"hostname"`
	Service  string `json:"service"`
}

type existingTunnel struct {
	ID      string              `json:"id"`
	Name    string              `json:"name"`
	Ingress []configuredIngress `json:"ingress"`
}

type authSnapshot struct {
	Generation       uint64           `json:"generation"`
	AuthorizationURL string           `json:"authorizationUrl"`
	State            authState        `json:"state"`
	AccountID        string           `json:"accountId"`
	Error            string           `json:"error"`
	Tunnels          []existingTunnel `json:"tunnels"`
	SelectedTunnelID string           `json:"selectedTunnelId"`
	SelectedHostname string           `json:"selectedHostname"`
}

type authCredential struct {
	accountID []byte
	apiToken  []byte
	endpoint  []byte
}

func decodeAuthCredential(certificate []byte) (*authCredential, error) {
	if len(certificate) == 0 || len(certificate) > maxOriginCertificateBytes {
		return nil, errors.New("origin certificate is invalid")
	}
	decoded := credentials.OriginCert{}
	err := consumeStrictOriginCertificate(certificate, func(payload []byte) error {
		return json.Unmarshal(payload, &decoded)
	})
	if err != nil {
		return nil, errors.New("origin certificate is invalid")
	}
	defer func() {
		decoded.ZoneID = ""
		decoded.AccountID = ""
		decoded.APIToken = ""
		decoded.Endpoint = ""
	}()
	if len(decoded.ZoneID) == 0 || !authAccountIDPattern.MatchString(decoded.AccountID) ||
		len(decoded.APIToken) == 0 || len(decoded.APIToken) > maxTokenBytes ||
		(decoded.Endpoint != "" && decoded.Endpoint != credentials.FedEndpoint) {
		return nil, errors.New("origin certificate is invalid")
	}
	return &authCredential{
		accountID: []byte(decoded.AccountID),
		apiToken:  []byte(decoded.APIToken),
		endpoint:  []byte(decoded.Endpoint),
	}, nil
}

func consumeStrictOriginCertificate(certificate []byte, consumer func([]byte) error) error {
	trimmedCertificate := bytes.TrimSpace(certificate)
	if !bytes.HasPrefix(trimmedCertificate, []byte("-----BEGIN ARGO TUNNEL TOKEN-----")) {
		return errors.New("origin certificate is invalid")
	}
	block, rest := pem.Decode(trimmedCertificate)
	return consumeDecodedOriginCertificateBlock(block, rest, consumer)
}

func consumeDecodedOriginCertificateBlock(
	block *pem.Block,
	rest []byte,
	consumer func([]byte) error,
) error {
	if block == nil {
		return errors.New("origin certificate is invalid")
	}
	defer wipe(block.Bytes)
	if block.Type != "ARGO TUNNEL TOKEN" || len(bytes.TrimSpace(rest)) != 0 {
		return errors.New("origin certificate is invalid")
	}
	return consumer(block.Bytes)
}

func (c *authCredential) account() string { return string(c.accountID) }
func (c *authCredential) token() string   { return string(c.apiToken) }

func (c *authCredential) clear() {
	if c == nil {
		return
	}
	wipe(c.accountID)
	wipe(c.apiToken)
	wipe(c.endpoint)
	c.accountID = nil
	c.apiToken = nil
	c.endpoint = nil
}

type authAPIFactory func(*authCredential) *readOnlyTunnelAPI

func defaultAuthAPIFactory(client httpDoer) authAPIFactory {
	return func(credential *authCredential) *readOnlyTunnelAPI {
		baseURL := cloudflareAPIURL
		if string(credential.endpoint) == credentials.FedEndpoint {
			baseURL = credentials.FedRampBaseApiURL
		}
		return newReadOnlyTunnelAPI(client, baseURL, credential)
	}
}

type authSession struct {
	generation uint64
	ctx        context.Context
	cancel     context.CancelFunc
	wg         sync.WaitGroup
	closeOnce  sync.Once

	mu         sync.Mutex
	snapshotV  authSnapshot
	credential *authCredential
	api        *readOnlyTunnelAPI
	apiFactory authAPIFactory
}

func beginAuthSession(
	generation uint64,
	transfer preparedLoginTransfer,
	apiFactory authAPIFactory,
) *authSession {
	ctx, cancel := context.WithCancel(context.Background())
	session := &authSession{
		generation: generation,
		ctx:        ctx,
		cancel:     cancel,
		apiFactory: apiFactory,
		snapshotV: authSnapshot{
			Generation:       generation,
			AuthorizationURL: boundText(transfer.authorization(), maxURLBytes),
			State:            authWaiting,
		},
	}
	session.wg.Add(1)
	go func() {
		defer session.wg.Done()
		certificate, err := transfer.wait(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			session.fail("browser login failed")
			return
		}
		defer wipe(certificate)
		credential, err := decodeAuthCredential(certificate)
		if err != nil {
			session.fail("origin certificate is invalid")
			return
		}
		session.mu.Lock()
		defer session.mu.Unlock()
		if session.ctx.Err() != nil || session.snapshotV.State == authStopped {
			credential.clear()
			return
		}
		session.credential = credential
		session.api = apiFactory(credential)
		session.snapshotV.State = authAuthorized
		session.snapshotV.AccountID = credential.account()
		session.snapshotV.Error = ""
	}()
	return session
}

func (s *authSession) fail(message string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.snapshotV.State == authStopped {
		return
	}
	s.snapshotV.State = authFailed
	s.snapshotV.Error = boundText(message, maxErrorBytes)
}

func (s *authSession) snapshot() authSnapshot {
	s.mu.Lock()
	defer s.mu.Unlock()
	snapshot := s.snapshotV
	snapshot.Tunnels = cloneTunnels(s.snapshotV.Tunnels)
	return snapshot
}

func (s *authSession) list(ctx context.Context) ([]existingTunnel, error) {
	operationCtx, api, finish, err := s.beginOperation(ctx)
	if err != nil {
		return nil, err
	}
	defer finish()

	tunnels, err := api.listExisting(operationCtx)
	if err != nil {
		return nil, errors.New(sanitizeError(err.Error(), api.secrets()))
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.ctx.Err() != nil || s.snapshotV.State != authAuthorized {
		return nil, context.Canceled
	}
	s.snapshotV.Tunnels = cloneTunnels(tunnels)
	return cloneTunnels(tunnels), nil
}

func (s *authSession) selectToken(ctx context.Context, tunnelID, hostname string) (string, error) {
	parsedID, err := uuid.Parse(tunnelID)
	if err != nil || parsedID == uuid.Nil {
		return "", errors.New("tunnel identifier is invalid")
	}
	canonicalHostname, err := normalizeHTTPSRoot(hostname)
	if err != nil {
		return "", err
	}
	operationCtx, api, finish, err := s.beginOperation(ctx)
	if err != nil {
		return "", err
	}
	defer finish()
	tunnels, err := api.listExisting(operationCtx)
	if err != nil {
		return "", errors.New(sanitizeError(err.Error(), api.secrets()))
	}
	var selected *existingTunnel
	for index := range tunnels {
		if tunnels[index].ID == parsedID.String() {
			selected = &tunnels[index]
			break
		}
	}
	if selected == nil {
		return "", errors.New("selected tunnel is unavailable")
	}

	token, err := api.getTunnelToken(operationCtx, parsedID.String())
	if err != nil {
		return "", errors.New(sanitizeError(err.Error(), api.secrets()))
	}
	s.mu.Lock()
	if s.ctx.Err() != nil || s.snapshotV.State != authAuthorized {
		s.mu.Unlock()
		return "", context.Canceled
	}
	s.snapshotV.SelectedTunnelID = selected.ID
	s.snapshotV.SelectedHostname = canonicalHostname
	s.mu.Unlock()
	return token, nil
}

func (s *authSession) beginOperation(
	parent context.Context,
) (context.Context, *readOnlyTunnelAPI, func(), error) {
	s.mu.Lock()
	if s.snapshotV.State != authAuthorized || s.api == nil {
		s.mu.Unlock()
		return nil, nil, nil, errors.New("browser login is not authorized")
	}
	api := s.api
	s.wg.Add(1)
	s.mu.Unlock()

	ctx, cancel := context.WithCancel(parent)
	stopSessionCancellation := context.AfterFunc(s.ctx, cancel)
	finishOnce := sync.Once{}
	finish := func() {
		finishOnce.Do(func() {
			stopSessionCancellation()
			cancel()
			s.wg.Done()
		})
	}
	return ctx, api, finish, nil
}

func (s *authSession) close() {
	s.closeOnce.Do(func() {
		s.cancel()
		s.mu.Lock()
		s.snapshotV = authSnapshot{Generation: s.generation, State: authStopped}
		s.mu.Unlock()
		s.wg.Wait()
		s.mu.Lock()
		if s.credential != nil {
			s.credential.clear()
		}
		s.credential = nil
		s.api = nil
		s.mu.Unlock()
	})
}

type authSessionManager struct {
	mu         sync.Mutex
	currentV   *authSession
	apiFactory authAPIFactory
	generation uint64
	closed     bool
}

func newAuthSessionManager(apiFactory authAPIFactory) *authSessionManager {
	return &authSessionManager{apiFactory: apiFactory}
}

func (m *authSessionManager) replace(generation uint64, transfer preparedLoginTransfer) *authSession {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed || generation == 0 || generation <= m.generation {
		return nil
	}
	m.generation = generation
	if m.currentV != nil {
		m.currentV.close()
	}
	session := beginAuthSession(generation, transfer, m.apiFactory)
	m.currentV = session
	return session
}

func (m *authSessionManager) current() *authSession {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.currentV
}

func (m *authSessionManager) close() {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return
	}
	m.closed = true
	if m.currentV != nil {
		m.currentV.close()
	}
	m.currentV = nil
}

type readOnlyTunnelAPI struct {
	client     httpDoer
	baseURL    *url.URL
	credential *authCredential
}

func newReadOnlyTunnelAPI(
	client httpDoer,
	baseURL string,
	credential *authCredential,
) *readOnlyTunnelAPI {
	parsed, _ := url.Parse(strings.TrimSuffix(baseURL, "/"))
	return &readOnlyTunnelAPI{client: client, baseURL: parsed, credential: credential}
}

func (a *readOnlyTunnelAPI) secrets() []string {
	return []string{a.credential.token()}
}

func (a *readOnlyTunnelAPI) listExisting(ctx context.Context) ([]existingTunnel, error) {
	endpoint := a.accountEndpoint("cfd_tunnel")
	query := endpoint.Query()
	query.Set("is_deleted", "false")
	query.Set("per_page", "100")
	query.Set("page", "1")
	endpoint.RawQuery = query.Encode()
	body, err := a.get(ctx, endpoint)
	if err != nil {
		return nil, err
	}
	defer wipe(body)
	var envelope struct {
		Success bool `json:"success"`
		Result  []struct {
			ID           string  `json:"id"`
			Name         string  `json:"name"`
			DeletedAt    *string `json:"deleted_at"`
			RemoteConfig bool    `json:"remote_config"`
			ConfigSource string  `json:"config_src"`
		} `json:"result"`
		Info struct {
			Page       int `json:"page"`
			PerPage    int `json:"per_page"`
			Count      int `json:"count"`
			TotalCount int `json:"total_count"`
		} `json:"result_info"`
	}
	if err := decodeStrictSingleJSON(body, &envelope); err != nil || !envelope.Success {
		return nil, errors.New("Cloudflare tunnel list response is invalid")
	}
	if envelope.Info.Page != 1 || envelope.Info.PerPage < 1 || envelope.Info.PerPage > 100 ||
		envelope.Info.Count != len(envelope.Result) ||
		envelope.Info.TotalCount != len(envelope.Result) ||
		len(envelope.Result) > maxExistingTunnels {
		return nil, errors.New("Cloudflare tunnel list exceeds supported bounds")
	}
	seen := make(map[uuid.UUID]struct{}, len(envelope.Result))
	tunnels := make([]existingTunnel, 0, len(envelope.Result))
	for _, value := range envelope.Result {
		if value.DeletedAt != nil {
			continue
		}
		id, parseErr := uuid.Parse(value.ID)
		if parseErr != nil || id == uuid.Nil {
			return nil, errors.New("Cloudflare returned an invalid tunnel identifier")
		}
		if _, exists := seen[id]; exists {
			return nil, errors.New("Cloudflare returned duplicate tunnel identifiers")
		}
		seen[id] = struct{}{}
		name := strings.TrimSpace(value.Name)
		if len(name) == 0 || len(name) > maxTunnelNameBytes || containsControl(name) {
			return nil, errors.New("Cloudflare returned an invalid tunnel name")
		}
		tunnel := existingTunnel{ID: id.String(), Name: name}
		if value.RemoteConfig || value.ConfigSource == "cloudflare" {
			tunnel.Ingress, err = a.getConfiguredIngress(ctx, id)
			if err != nil {
				return nil, err
			}
		}
		tunnels = append(tunnels, tunnel)
	}
	return tunnels, nil
}

func (a *readOnlyTunnelAPI) getConfiguredIngress(
	ctx context.Context,
	tunnelID uuid.UUID,
) ([]configuredIngress, error) {
	endpoint := a.accountEndpoint("cfd_tunnel", tunnelID.String(), "configurations")
	body, err := a.get(ctx, endpoint)
	if err != nil {
		return nil, err
	}
	defer wipe(body)
	var envelope struct {
		Success bool `json:"success"`
		Result  struct {
			Config struct {
				Ingress []struct {
					Hostname string `json:"hostname"`
					Service  string `json:"service"`
				} `json:"ingress"`
			} `json:"config"`
		} `json:"result"`
	}
	if err := decodeStrictSingleJSON(body, &envelope); err != nil || !envelope.Success ||
		len(envelope.Result.Config.Ingress) > maxConfiguredIngress {
		return nil, errors.New("Cloudflare tunnel configuration response is invalid")
	}
	seen := make(map[string]struct{})
	ingress := make([]configuredIngress, 0, len(envelope.Result.Config.Ingress))
	for _, rule := range envelope.Result.Config.Ingress {
		if rule.Hostname == "" {
			continue
		}
		hostname, normalizeErr := normalizeDNSHostname(rule.Hostname)
		if normalizeErr != nil || len(rule.Service) == 0 || len(rule.Service) > maxServiceBytes ||
			containsControl(rule.Service) {
			return nil, errors.New("Cloudflare returned an invalid ingress rule")
		}
		if _, duplicate := seen[hostname]; duplicate {
			return nil, errors.New("Cloudflare returned duplicate ingress hostnames")
		}
		seen[hostname] = struct{}{}
		ingress = append(ingress, configuredIngress{Hostname: hostname, Service: rule.Service})
	}
	return ingress, nil
}

func (a *readOnlyTunnelAPI) getTunnelToken(ctx context.Context, tunnelID string) (string, error) {
	id, err := uuid.Parse(tunnelID)
	if err != nil || id == uuid.Nil {
		return "", errors.New("tunnel identifier is invalid")
	}
	endpoint := a.accountEndpoint("cfd_tunnel", id.String(), "token")
	body, err := a.get(ctx, endpoint)
	if err != nil {
		return "", err
	}
	defer wipe(body)
	var envelope struct {
		Success bool   `json:"success"`
		Result  string `json:"result"`
	}
	if err := decodeStrictSingleJSON(body, &envelope); err != nil || !envelope.Success ||
		len(envelope.Result) == 0 || len(envelope.Result) > maxTokenBytes {
		return "", errors.New("Cloudflare tunnel token response is invalid")
	}
	parsedToken, err := parseTunnelToken(envelope.Result)
	if err != nil {
		return "", errors.New("Cloudflare tunnel token response is invalid")
	}
	defer wipe(parsedToken.TunnelSecret)
	if parsedToken.TunnelID != id || parsedToken.AccountTag != a.credential.account() {
		return "", errors.New("Cloudflare tunnel token response is invalid")
	}
	return envelope.Result, nil
}

func (a *readOnlyTunnelAPI) get(ctx context.Context, endpoint url.URL) ([]byte, error) {
	if a.client == nil || a.baseURL == nil || a.baseURL.Scheme != "https" || a.baseURL.Host == "" {
		return nil, errors.New("Cloudflare API is unavailable")
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint.String(), nil)
	if err != nil || request.Method != http.MethodGet {
		return nil, errors.New("could not construct read-only Cloudflare request")
	}
	request.Header.Set("Authorization", "Bearer "+a.credential.token())
	request.Header.Set("Accept", "application/json")
	request.Header.Set("User-Agent", "WeKit cloudflared bridge/"+cloudflaredVersion)
	response, err := a.client.Do(request)
	if err != nil {
		if ctx.Err() != nil {
			return nil, context.Canceled
		}
		return nil, errors.New("Cloudflare API request failed")
	}
	body, readErr := readBoundedBody(response.Body, maxAPIResponseBytes)
	response.Body.Close()
	if readErr != nil {
		return nil, errors.New("Cloudflare API response is too large")
	}
	if response.StatusCode != http.StatusOK {
		wipe(body)
		return nil, fmt.Errorf("Cloudflare API returned HTTP %d", response.StatusCode)
	}
	return body, nil
}

func (a *readOnlyTunnelAPI) accountEndpoint(parts ...string) url.URL {
	endpoint := *a.baseURL
	segments := append([]string{"accounts", a.credential.account()}, parts...)
	endpoint.Path = path.Join(append([]string{endpoint.Path}, segments...)...)
	return endpoint
}

func normalizeDNSHostname(raw string) (string, error) {
	if raw == "" || raw != strings.TrimSpace(raw) || len(raw) > 253 ||
		strings.ContainsAny(raw, "/:@?#[]") {
		return "", errors.New("hostname is invalid")
	}
	hostname := strings.ToLower(strings.TrimSuffix(raw, "."))
	if net.ParseIP(hostname) != nil || !strings.Contains(hostname, ".") {
		return "", errors.New("hostname is invalid")
	}
	labels := strings.Split(hostname, ".")
	for _, label := range labels {
		if !dnsLabelPattern.MatchString(label) {
			return "", errors.New("hostname is invalid")
		}
	}
	return hostname, nil
}

func normalizeHTTPSRoot(raw string) (string, error) {
	parsed, err := url.ParseRequestURI(raw)
	if err != nil || parsed.Scheme != "https" || parsed.Host == "" || parsed.User != nil ||
		parsed.RawQuery != "" || parsed.Fragment != "" || (parsed.Path != "" && parsed.Path != "/") ||
		parsed.Port() != "" {
		return "", errors.New("hostname must be an HTTPS root URL")
	}
	hostname, err := normalizeDNSHostname(parsed.Hostname())
	if err != nil {
		return "", errors.New("hostname must be an HTTPS root URL")
	}
	return "https://" + hostname, nil
}

func decodeStrictSingleJSON(body []byte, destination any) error {
	decoder := json.NewDecoder(bytes.NewReader(body))
	if err := decoder.Decode(destination); err != nil {
		return err
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return errors.New("response contains trailing JSON")
	}
	return nil
}

func readBoundedBody(body io.Reader, limit int) ([]byte, error) {
	reader := io.LimitReader(body, int64(limit)+1)
	payload, err := io.ReadAll(reader)
	if err != nil {
		wipe(payload)
		return nil, err
	}
	if len(payload) > limit {
		wipe(payload)
		return nil, errors.New("response exceeds limit")
	}
	return payload, nil
}

func cloneTunnels(values []existingTunnel) []existingTunnel {
	cloned := make([]existingTunnel, len(values))
	for index, value := range values {
		cloned[index] = value
		cloned[index].Ingress = append([]configuredIngress(nil), value.Ingress...)
	}
	return cloned
}

func containsControl(value string) bool {
	for _, character := range value {
		if character < 0x20 || character == 0x7f {
			return true
		}
	}
	return false
}

func wipe(value []byte) {
	for index := range value {
		value[index] = 0
	}
}
