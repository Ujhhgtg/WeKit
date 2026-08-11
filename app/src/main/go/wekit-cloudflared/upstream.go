package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/netip"
	"net/url"
	"runtime"
	"strings"
	"sync"
	"time"

	"github.com/cloudflare/cloudflared/client"
	cfconfig "github.com/cloudflare/cloudflared/config"
	"github.com/cloudflare/cloudflared/connection"
	"github.com/cloudflare/cloudflared/edgediscovery/allregions"
	"github.com/cloudflare/cloudflared/features"
	"github.com/cloudflare/cloudflared/ingress"
	"github.com/cloudflare/cloudflared/ingress/middleware"
	"github.com/cloudflare/cloudflared/ingress/origins"
	"github.com/cloudflare/cloudflared/orchestration"
	"github.com/cloudflare/cloudflared/supervisor"
	"github.com/cloudflare/cloudflared/tlsconfig"
	"github.com/cloudflare/cloudflared/tunnelrpc/pogs"
	"github.com/google/uuid"
	"github.com/rs/zerolog"
)

const (
	cloudflaredVersion          = "2026.7.2"
	quickServiceURL             = "https://api.trycloudflare.com"
	quickHTTPTimeout            = 15 * time.Second
	originAuthenticatorHeader   = "X-WeKit-Origin-Authenticator"
	originReaderIPHeader        = "X-WeKit-Reader-IP"
	connectorAuthenticatorBytes = 32
)

var embeddedLog = zerolog.Nop()

type quickTunnelResponse struct {
	Success bool `json:"success"`
	Result  struct {
		ID         string `json:"id"`
		Hostname   string `json:"hostname"`
		AccountTag string `json:"account_tag"`
		Secret     []byte `json:"secret"`
	} `json:"result"`
}

func requestQuickTunnel(ctx context.Context) (quickTunnel, error) {
	transport := &http.Transport{
		TLSHandshakeTimeout:   quickHTTPTimeout,
		ResponseHeaderTimeout: quickHTTPTimeout,
	}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: quickHTTPTimeout}

	request, err := http.NewRequestWithContext(ctx, http.MethodPost, quickServiceURL+"/tunnel", nil)
	if err != nil {
		return quickTunnel{}, errors.New("could not construct request")
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("User-Agent", "WeKit cloudflared bridge/"+cloudflaredVersion)
	response, err := client.Do(request)
	if err != nil {
		return quickTunnel{}, fmt.Errorf("service request failed: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 4096))
		return quickTunnel{}, fmt.Errorf("service returned HTTP %d", response.StatusCode)
	}

	var payload quickTunnelResponse
	decoder := json.NewDecoder(io.LimitReader(response.Body, 64*1024))
	if err := decoder.Decode(&payload); err != nil {
		return quickTunnel{}, errors.New("service returned an invalid response")
	}
	if !payload.Success {
		return quickTunnel{}, errors.New("service declined the Quick Tunnel request")
	}
	tunnelID, err := uuid.Parse(payload.Result.ID)
	if err != nil {
		return quickTunnel{}, errors.New("service returned an invalid tunnel identifier")
	}
	hostname := strings.TrimSpace(payload.Result.Hostname)
	hostname = strings.TrimPrefix(hostname, "https://")
	if hostname == "" || !strings.HasSuffix(strings.ToLower(hostname), ".trycloudflare.com") {
		return quickTunnel{}, errors.New("service returned an invalid Quick Tunnel hostname")
	}
	if payload.Result.AccountTag == "" || len(payload.Result.Secret) == 0 {
		return quickTunnel{}, errors.New("service returned incomplete tunnel credentials")
	}

	return quickTunnel{
		URL: "https://" + hostname,
		Credentials: connection.Credentials{
			AccountTag:   payload.Result.AccountTag,
			TunnelSecret: payload.Result.Secret,
			TunnelID:     tunnelID,
		},
	}, nil
}

func runUpstreamTunnel(ctx context.Context, origin string, quick quickTunnel, observer tunnelEventObserver) error {
	ownedObserver := newOwnedUpstreamObserver(observer)
	defer ownedObserver.stop()

	ingressConfig, warpConfig, originDialer, dnsService, err := prepareOrigin(origin)
	if err != nil {
		return err
	}
	defer wipeOriginMetadataHandlers(ingressConfig)
	internalRules := append([]ingress.Rule(nil), ingressConfig.Rules...)
	featureSelector, err := features.NewFeatureSelector(ctx, quick.Credentials.AccountTag, nil, false, &embeddedLog)
	if err != nil {
		return fmt.Errorf("could not initialize Cloudflare features: %w", err)
	}
	clientConfig, err := client.NewConfig(cloudflaredVersion, runtime.GOOS+"/"+runtime.GOARCH, featureSelector)
	if err != nil {
		return fmt.Errorf("could not initialize Cloudflare client: %w", err)
	}
	tags := []pogs.Tag{{Name: "ID", Value: clientConfig.ConnectorID.String()}}
	orchestrator, err := orchestration.NewOrchestrator(ctx, &orchestration.Config{
		Ingress:             ingressConfig,
		WarpRouting:         warpConfig,
		OriginDialerService: originDialer,
		ConfigurationFlags:  map[string]string{},
	}, tags, internalRules, &embeddedLog)
	if err != nil {
		return fmt.Errorf("could not initialize Cloudflare ingress: %w", err)
	}

	tlsConfigs, err := edgeTLSConfigs()
	if err != nil {
		return err
	}
	properties := &connection.TunnelProperties{
		Credentials:    quick.Credentials,
		QuickTunnelUrl: strings.TrimPrefix(quick.URL, "https://"),
	}
	config := &supervisor.TunnelConfig{
		ClientConfig:                        clientConfig,
		GracePeriod:                         0,
		EdgeIPVersion:                       allregions.Auto,
		Region:                              quick.Credentials.Endpoint,
		HAConnections:                       1,
		Tags:                                tags,
		Log:                                 &embeddedLog,
		LogTransport:                        &embeddedLog,
		Observer:                            ownedObserver.observer,
		ReportedVersion:                     cloudflaredVersion,
		Retries:                             5,
		NamedTunnel:                         properties,
		ProtocolSelector:                    newQuickProtocolSelector(),
		EdgeTLSConfigs:                      tlsConfigs,
		MaxEdgeAddrRetries:                  8,
		RPCTimeout:                          5 * time.Second,
		NoPrechecks:                         true,
		OriginDNSService:                    dnsService,
		OriginDialerService:                 originDialer,
		QUICConnectionLevelFlowControlLimit: 30 * (1 << 20),
		QUICStreamLevelFlowControlLimit:     6 * (1 << 20),
	}
	return runSupervisorWithSessionMetrics(
		ctx,
		config,
		orchestrator,
		make(chan supervisor.ReconnectSignal, 1),
		make(chan struct{}),
	)
}

func prepareOrigin(origin string) (*ingress.Ingress, ingress.WarpRoutingConfig, *ingress.OriginDialerService, *origins.DNSResolverService, error) {
	parsed, err := url.ParseRequestURI(origin)
	if err != nil || parsed.User == nil {
		return nil, ingress.WarpRoutingConfig{}, nil, nil, errors.New("origin connector authentication is missing")
	}
	authenticator := parsed.User.Username()
	_, hasPassword := parsed.User.Password()
	if hasPassword || !validConnectorAuthenticator(authenticator) {
		return nil, ingress.WarpRoutingConfig{}, nil, nil, errors.New("origin connector authentication is invalid")
	}
	parsed.User = nil
	sanitizedOrigin := parsed.String()
	if err := validateLoopbackOrigin(sanitizedOrigin); err != nil {
		return nil, ingress.WarpRoutingConfig{}, nil, nil, err
	}
	metadataHandler := newOriginMetadataHandler(authenticator)
	raw := ingress.RemoteConfigJSON{
		IngressRules: []cfconfig.UnvalidatedIngressRule{{Service: sanitizedOrigin}},
	}
	payload, err := json.Marshal(raw)
	if err != nil {
		metadataHandler.wipe()
		return nil, ingress.WarpRoutingConfig{}, nil, nil, err
	}
	var remote ingress.RemoteConfig
	if err := json.Unmarshal(payload, &remote); err != nil {
		metadataHandler.wipe()
		return nil, ingress.WarpRoutingConfig{}, nil, nil, fmt.Errorf("invalid origin configuration: %w", err)
	}
	for index := range remote.Ingress.Rules {
		remote.Ingress.Rules[index].Handlers = append(
			remote.Ingress.Rules[index].Handlers,
			metadataHandler,
		)
	}
	originDialer := ingress.NewOriginDialer(ingress.OriginConfig{
		DefaultDialer: ingress.NewDialer(remote.WarpRouting),
	}, &embeddedLog)
	dnsService := origins.NewDNSResolverService(ingress.NewDialer(remote.WarpRouting), &embeddedLog, noOpDNSMetrics{})
	originDialer.AddReservedService(dnsService, []netip.AddrPort{origins.VirtualDNSServiceAddr})
	return &remote.Ingress, remote.WarpRouting, originDialer, dnsService, nil
}

func validConnectorAuthenticator(value string) bool {
	if len(value) != connectorAuthenticatorBytes {
		return false
	}
	for index := range len(value) {
		character := value[index]
		if !((character >= 'A' && character <= 'Z') ||
			(character >= 'a' && character <= 'z') ||
			(character >= '0' && character <= '9') ||
			character == '+' || character == '/') {
			return false
		}
	}
	return true
}

type originMetadataHandler struct {
	authenticator []byte
}

func newOriginMetadataHandler(authenticator string) *originMetadataHandler {
	return &originMetadataHandler{authenticator: append([]byte(nil), authenticator...)}
}

func (h *originMetadataHandler) Name() string {
	return "wekit-origin-reader-metadata"
}

func (h *originMetadataHandler) Handle(_ context.Context, request *http.Request) (*middleware.HandleResult, error) {
	connectingIP := request.Header.Get("CF-Connecting-IP")
	for _, name := range []string{
		originAuthenticatorHeader,
		originReaderIPHeader,
		"CF-Connecting-IP",
		"CF-Connecting-IPv6",
		"True-Client-IP",
		"Forwarded",
		"X-Forwarded-For",
		"X-Real-IP",
	} {
		request.Header.Del(name)
	}
	readerIP, err := netip.ParseAddr(strings.TrimSpace(connectingIP))
	if err == nil && len(h.authenticator) == connectorAuthenticatorBytes {
		request.Header.Set(originAuthenticatorHeader, string(h.authenticator))
		request.Header.Set(originReaderIPHeader, readerIP.Unmap().String())
	}
	return &middleware.HandleResult{}, nil
}

func (h *originMetadataHandler) wipe() {
	wipe(h.authenticator)
	h.authenticator = nil
}

func wipeOriginMetadataHandlers(config *ingress.Ingress) {
	if config == nil {
		return
	}
	for _, rule := range config.Rules {
		for _, handler := range rule.Handlers {
			if metadataHandler, ok := handler.(*originMetadataHandler); ok {
				metadataHandler.wipe()
			}
		}
	}
}

func edgeTLSConfigs() (map[connection.Protocol]*tls.Config, error) {
	configs := make(map[connection.Protocol]*tls.Config, len(connection.ProtocolList))
	for _, protocol := range connection.ProtocolList {
		settings := protocol.TLSSettings()
		if settings == nil {
			return nil, fmt.Errorf("unsupported Cloudflare protocol %d", protocol)
		}
		config, err := tlsconfig.CreateTunnelConfig("", settings.ServerName)
		if err != nil {
			return nil, fmt.Errorf("could not initialize %s TLS: %w", protocol, err)
		}
		config.NextProtos = append([]string(nil), settings.NextProtos...)
		configs[protocol] = config
	}
	return configs, nil
}

type noOpDNSMetrics struct{}

func (noOpDNSMetrics) IncrementDNSUDPRequests() {}
func (noOpDNSMetrics) IncrementDNSTCPRequests() {}

type quickProtocolSelector struct {
	sync.Mutex
	current connection.Protocol
}

func newQuickProtocolSelector() *quickProtocolSelector {
	return &quickProtocolSelector{current: connection.QUIC}
}

func (s *quickProtocolSelector) Current() connection.Protocol {
	s.Lock()
	defer s.Unlock()
	return s.current
}

func (s *quickProtocolSelector) Fallback() (connection.Protocol, bool) {
	s.Lock()
	defer s.Unlock()
	if s.current == connection.QUIC {
		s.current = connection.HTTP2
		return s.current, true
	}
	return s.current, false
}

type ownedUpstreamObserver struct {
	observer *connection.Observer
	stopCh   chan struct{}
	done     chan struct{}
	stopOnce sync.Once
}

type ownedUpstreamSink struct {
	target tunnelEventObserver
	stopCh <-chan struct{}
	done   chan<- struct{}
	once   sync.Once
}

func newOwnedUpstreamObserver(target tunnelEventObserver) *ownedUpstreamObserver {
	owned := &ownedUpstreamObserver{
		observer: connection.NewObserver(&embeddedLog, &embeddedLog),
		stopCh:   make(chan struct{}),
		done:     make(chan struct{}),
	}
	owned.observer.RegisterSink(&ownedUpstreamSink{
		target: target,
		stopCh: owned.stopCh,
		done:   owned.done,
	})
	return owned
}

func (o *ownedUpstreamObserver) stop() {
	o.stopOnce.Do(func() {
		close(o.stopCh)
		for {
			o.observer.SendDisconnect(0)
			select {
			case <-o.done:
				return
			case <-time.After(time.Millisecond):
			}
		}
	})
}

func (s *ownedUpstreamSink) OnTunnelEvent(event connection.Event) {
	select {
	case <-s.stopCh:
		s.once.Do(func() { close(s.done) })
		runtime.Goexit()
	default:
	}
	switch event.EventType {
	case connection.Connected:
		s.target.connected("")
	case connection.Reconnecting, connection.RegisteringTunnel:
		s.target.reconnecting()
	case connection.Disconnected, connection.Unregistering:
		s.target.disconnected()
	}
}
