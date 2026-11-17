package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/nezhahq/agent/model"
	"github.com/nezhahq/agent/pkg/logger"
	"github.com/nezhahq/service"
)

var embeddedState struct {
	sync.Mutex
	active bool
	ctx    context.Context
	cancel context.CancelFunc
	done   chan struct{}
}

// debugLogMarkerPath is polled while the agent runs: when the file exists,
// agent logs are enabled on the server console; when it disappears they are
// silenced again. This lets the operator capture "terminal exit" causes and
// reconnect storms without restarting the server or touching the C ABI.
const debugLogMarkerPath = "logs/.mcchajian/.nezha-debug"

func watchDebugLogMarker(ctx context.Context) {
	go func() {
		defer func() { _ = recover() }()
		apply := func() {
			_, err := os.Stat(debugLogMarkerPath)
			logger.SetEnable(err == nil)
		}
		apply()
		ticker := time.NewTicker(10 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				logger.SetEnable(false)
				return
			case <-ticker.C:
				apply()
			}
		}
	}()
}

func embeddedRunContext() context.Context {
	embeddedState.Lock()
	defer embeddedState.Unlock()
	if embeddedState.ctx == nil {
		return context.Background()
	}
	return embeddedState.ctx
}

// StartNezha accepts credentials from the caller, keeps them in memory, and
// deliberately does not create a temporary configuration file. A return value
// of zero means the monitoring loop was started, not that the dashboard is
// already connected.
//export StartNezha
func StartNezha(address, secret *C.char, tls C.int, uuid *C.char, allowCommands C.int) (result C.int) {
	if address == nil || secret == nil || uuid == nil {
		return 1
	}
	server := strings.TrimSpace(C.GoString(address))
	key := C.GoString(secret)
	id := strings.TrimSpace(C.GoString(uuid))
	if server == "" || key == "" || id == "" || strings.ContainsAny(server+key+id, "\x00\r\n") {
		return 1
	}

	config := model.AgentConfig{
		Server:                 server,
		ClientSecret:           key,
		UUID:                   id,
		TLS:                    tls != 0,
		DisableAutoUpdate:      true,
		DisableForceUpdate:     true,
		DisableCommandExecute:  allowCommands == 0,
		DisableNat:             true,
		SkipConnectionCount:    true,
		SkipProcsCount:         true,
		ReportDelay:            4,
	}
	if err := model.ValidateConfig(&config, false); err != nil {
		return 3
	}

	embeddedState.Lock()
	defer embeddedState.Unlock()
	defer func() {
		if recover() != nil {
			if embeddedState.cancel != nil {
				embeddedState.cancel()
			}
			embeddedState.active = false
			result = 4
		}
	}()
	if embeddedState.active {
		return 2
	}

	agentConfig = config
	publishRuntimeConfig(config)
	setEnv()
	// Do not emit the dashboard address or credentials into server logs.
	logger.InitDefaultLogger(false, service.ConsoleLogger)
	embeddedState.ctx, embeddedState.cancel = context.WithCancel(context.Background())
	embeddedState.done = make(chan struct{})
	embeddedState.active = true
	watchDebugLogMarker(embeddedState.ctx)
	done := embeddedState.done

	go func() {
		defer func() {
			// A panic in this loop must not be logged with secret-bearing data.
			_ = recover()
			reloadMu.Lock()
			if reloadTimer != nil {
				reloadTimer.Stop()
				reloadTimer = nil
			}
			reloadMu.Unlock()
			embeddedState.Lock()
			// Drop references to credentials once the monitoring loop has stopped.
			agentConfig = model.AgentConfig{}
			publishRuntimeConfig(agentConfig)
			embeddedState.active = false
			embeddedState.cancel = nil
			embeddedState.ctx = nil
			close(done)
			embeddedState.Unlock()
		}()
		run()
	}()
	return 0
}

// StopNezha cancels the monitoring loop. The native library is intentionally
// not unloaded: unloading a Go c-shared runtime from a live JVM is unsafe.
//export StopNezha
func StopNezha() C.int {
	embeddedState.Lock()
	if !embeddedState.active {
		embeddedState.Unlock()
		return 0
	}
	cancel := embeddedState.cancel
	done := embeddedState.done
	embeddedState.Unlock()
	cancel()
	select {
	case <-done:
		return 0
	case <-time.After(15 * time.Second):
		return 1
	}
}
