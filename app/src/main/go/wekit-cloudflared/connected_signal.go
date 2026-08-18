package main

import (
	"context"
	"sync"

	"github.com/cloudflare/cloudflared/orchestration"
	cloudflaredsignal "github.com/cloudflare/cloudflared/signal"
	"github.com/cloudflare/cloudflared/supervisor"
	"github.com/prometheus/client_golang/prometheus"
)

var supervisorMetricsMu sync.Mutex

// runSupervisorWithSessionMetrics contains the only use of cloudflared's safe_signal package.
// Despite its package name it does not import os/signal or install a process handler: it is the
// pinned public Supervisor API's sync.Once-protected close(channel) notification primitive.
func runSupervisorWithSessionMetrics(
	ctx context.Context,
	config *supervisor.TunnelConfig,
	orchestrator *orchestration.Orchestrator,
	reconnectCh chan supervisor.ReconnectSignal,
	graceShutdownC <-chan struct{},
) error {
	// NewSupervisor currently hard-codes prometheus.DefaultRegisterer for its
	// QUIC v3 metrics. Give each embedded session an isolated registry while the
	// supervisor is constructed, then restore the process defaults immediately.
	registry := prometheus.NewRegistry()
	tunnelSupervisor, err := func() (*supervisor.Supervisor, error) {
		supervisorMetricsMu.Lock()
		defer supervisorMetricsMu.Unlock()
		previousRegisterer := prometheus.DefaultRegisterer
		previousGatherer := prometheus.DefaultGatherer
		prometheus.DefaultRegisterer = registry
		prometheus.DefaultGatherer = registry
		defer func() {
			prometheus.DefaultRegisterer = previousRegisterer
			prometheus.DefaultGatherer = previousGatherer
		}()
		return supervisor.NewSupervisor(config, orchestrator, reconnectCh, graceShutdownC)
	}()
	if err != nil {
		return err
	}
	connected := cloudflaredsignal.New(make(chan struct{}))
	return tunnelSupervisor.Run(ctx, connected)
}
