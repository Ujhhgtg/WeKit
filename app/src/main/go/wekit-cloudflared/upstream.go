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
	"github.com/cloudflare/cloudflared/ingress/origins"
	"github.com/cloudflare/cloudflared/orchestration"
	"github.com/cloudflare/cloudflared/signal"
	"github.com/cloudflare/cloudflared/supervisor"
	"github.com/cloudflare/cloudflared/tlsconfig"
	"github.com/cloudflare/cloudflared/tunnelrpc/pogs"
	"github.com/google/uuid"
	"github.com/rs/zerolog"
)

const (
	cloudflaredVersion = "2026.7.2"
	quickServiceURL    = "https://api.trycloudflare.com"
	quickHTTPTimeout   = 15 * time.Second
)

var (
	embeddedLog      = zerolog.Nop()
	embeddedObserver = newEmbeddedObserver()
	activeObserver   struct {
		sync.Mutex
		observer tunnelEventObserver
		used     bool
	}
)

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
	if err := setActiveObserver(observer); err != nil {
		return err
	}
	defer clearActiveObserver(observer)

	ingressConfig, warpConfig, originDialer, dnsService, err := prepareOrigin(origin)
	if err != nil {
		return err
	}
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
	}, tags, nil, &embeddedLog)
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
		HAConnections:                       1,
		Tags:                                tags,
		Log:                                 &embeddedLog,
		LogTransport:                        &embeddedLog,
		Observer:                            embeddedObserver,
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
	connectedSignal := signal.New(make(chan struct{}))
	return supervisor.StartTunnelDaemon(
		ctx,
		config,
		orchestrator,
		connectedSignal,
		make(chan supervisor.ReconnectSignal, 1),
		make(chan struct{}),
	)
}

func prepareOrigin(origin string) (*ingress.Ingress, ingress.WarpRoutingConfig, *ingress.OriginDialerService, *origins.DNSResolverService, error) {
	raw := ingress.RemoteConfigJSON{
		IngressRules: []cfconfig.UnvalidatedIngressRule{{Service: origin}},
	}
	payload, err := json.Marshal(raw)
	if err != nil {
		return nil, ingress.WarpRoutingConfig{}, nil, nil, err
	}
	var remote ingress.RemoteConfig
	if err := json.Unmarshal(payload, &remote); err != nil {
		return nil, ingress.WarpRoutingConfig{}, nil, nil, fmt.Errorf("invalid origin configuration: %w", err)
	}
	originDialer := ingress.NewOriginDialer(ingress.OriginConfig{
		DefaultDialer: ingress.NewDialer(remote.WarpRouting),
	}, &embeddedLog)
	dnsService := origins.NewDNSResolverService(ingress.NewDialer(remote.WarpRouting), &embeddedLog, noOpDNSMetrics{})
	originDialer.AddReservedService(dnsService, []netip.AddrPort{origins.VirtualDNSServiceAddr})
	return &remote.Ingress, remote.WarpRouting, originDialer, dnsService, nil
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

func newEmbeddedObserver() *connection.Observer {
	observer := connection.NewObserver(&embeddedLog, &embeddedLog)
	observer.RegisterSink(connection.EventSinkFunc(forwardUpstreamEvent))
	return observer
}

func forwardUpstreamEvent(event connection.Event) {
	activeObserver.Lock()
	observer := activeObserver.observer
	activeObserver.Unlock()
	if observer == nil {
		return
	}
	switch event.EventType {
	case connection.Connected:
		observer.connected("")
	case connection.Reconnecting, connection.RegisteringTunnel:
		observer.reconnecting()
	case connection.Disconnected, connection.Unregistering:
		observer.disconnected()
	}
}

func setActiveObserver(observer tunnelEventObserver) error {
	activeObserver.Lock()
	defer activeObserver.Unlock()
	if activeObserver.observer != nil {
		return errors.New("only one embedded Cloudflare tunnel may run at a time")
	}
	if activeObserver.used {
		return errors.New("this proof of concept supports one Quick Tunnel session per process")
	}
	activeObserver.observer = observer
	activeObserver.used = true
	return nil
}

func clearActiveObserver(observer tunnelEventObserver) {
	activeObserver.Lock()
	defer activeObserver.Unlock()
	if activeObserver.observer == observer {
		activeObserver.observer = nil
	}
}
