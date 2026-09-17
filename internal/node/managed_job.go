package node

import (
	"context"
	"fmt"
	"sync"
)

// ManagedJob serializes cancellable, one-shot work. Stop requests cancellation
// without blocking the web server; Start remains disabled until work exits.
type ManagedJob struct {
	mu      sync.Mutex
	parent  context.Context
	run     func(context.Context) (string, error)
	cancel  context.CancelFunc
	done    chan struct{}
	message string
}

func NewManagedJob(parent context.Context, run func(context.Context) (string, error)) *ManagedJob {
	return &ManagedJob{parent: parent, run: run, message: "Idle; Start runs one sync."}
}

func (j *ManagedJob) Start() error {
	j.mu.Lock()
	defer j.mu.Unlock()
	if err := j.parent.Err(); err != nil {
		return err
	}
	if j.cancel != nil {
		return nil
	}
	ctx, cancel := context.WithCancel(j.parent)
	j.cancel, j.done = cancel, make(chan struct{})
	j.message = "Sync running. Refresh to see progress."
	go func() {
		message, err := j.run(ctx)
		j.mu.Lock()
		defer j.mu.Unlock()
		if ctx.Err() != nil {
			j.message = "Sync stopped."
		} else if err != nil {
			// Provider errors may contain credentials; never send them to the page.
			j.message = "Sync failed. Check the source, state directory and provider configuration."
		} else {
			j.message = fmt.Sprintf("Sync completed. %s", message)
		}
		cancel()
		j.cancel = nil
		close(j.done)
	}()
	return nil
}

func (j *ManagedJob) Stop() {
	j.mu.Lock()
	defer j.mu.Unlock()
	if j.cancel != nil {
		j.message = "Stopping; waiting for current work to finish."
		j.cancel()
	}
}

func (j *ManagedJob) Snapshot() ManagedState {
	j.mu.Lock()
	defer j.mu.Unlock()
	return ManagedState{Running: j.cancel != nil, Message: j.message}
}

// Wait is used only at process shutdown, after cancelling the parent context.
func (j *ManagedJob) Wait() {
	j.mu.Lock()
	done := j.done
	j.mu.Unlock()
	if done != nil {
		<-done
	}
}
