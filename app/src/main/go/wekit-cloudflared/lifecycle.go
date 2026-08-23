package main

import "sync"

// callbackDispatcher is an unbounded, single-consumer callback queue. Producers
// never execute foreign callbacks directly, so tunnel shutdown can first join
// every producer and then drain (external stop) or discard (reentrant stop) the
// remaining queue before releasing callback-owned state.
type callbackDispatcher struct {
	mu       sync.Mutex
	ready    *sync.Cond
	queue    []callbackInvocation
	closed   bool
	discard  bool
	callback bridgeCallback
	done     chan struct{}
}

type callbackInvocation struct {
	callback bridgeCallback
	event    bridgeEvent
}

func newCallbackDispatcher(callback bridgeCallback) *callbackDispatcher {
	dispatcher := &callbackDispatcher{
		callback: callback,
		done:     make(chan struct{}),
	}
	dispatcher.ready = sync.NewCond(&dispatcher.mu)
	go dispatcher.run()
	return dispatcher
}

func (d *callbackDispatcher) enqueue(event bridgeEvent) {
	d.enqueueWith(d.callback, event)
}

func (d *callbackDispatcher) enqueueWith(callback bridgeCallback, event bridgeEvent) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.closed {
		return
	}
	d.queue = append(d.queue, callbackInvocation{callback: callback, event: event})
	d.ready.Signal()
}

func (d *callbackDispatcher) close(discard bool) {
	d.mu.Lock()
	d.closed = true
	if discard {
		d.discard = true
		d.queue = nil
	}
	d.ready.Broadcast()
	d.mu.Unlock()
}

func (d *callbackDispatcher) run() {
	defer close(d.done)
	for {
		d.mu.Lock()
		for len(d.queue) == 0 && !d.closed {
			d.ready.Wait()
		}
		if d.discard || (d.closed && len(d.queue) == 0) {
			d.mu.Unlock()
			return
		}
		invocation := d.queue[0]
		d.queue[0] = callbackInvocation{}
		d.queue = d.queue[1:]
		d.mu.Unlock()

		if invocation.callback != nil {
			invocation.callback(invocation.event)
		}
	}
}
