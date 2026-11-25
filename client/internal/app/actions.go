// Package app wires the backend, OBS and hotkeys into the running client.
package app

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"gitea.defaoite.dev/dylan/vod-system/client/internal/api"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/control"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/notify"
)

// Action names, shared by hotkeys and control commands.
const (
	ActionClip    = "clip"
	ActionMarker  = "mark"
	ActionSection = "section"
	ActionStatus  = "status"
)

// Backend is the part of the API the actions use.
type Backend interface {
	Clip(ctx context.Context, duration float64, title string) (string, error)
	CreateMarker(ctx context.Context, message string) (api.Marker, error)
	SaveSectionByMarkers(ctx context.Context, startMarkerID, endMarkerID int64, title string) (string, error)
	Job(ctx context.Context, uuid string) (api.Job, error)
}

// Actions performs the hotkey actions. It is safe for concurrent use.
type Actions struct {
	Backend     Backend
	Notifier    notify.Notifier
	ClipSeconds float64
	// Now and PollInterval are overridable for tests.
	Now          func() time.Time
	PollInterval time.Duration
	JobTimeout   time.Duration

	mu           sync.Mutex
	sectionStart *api.Marker
	jobs         sync.WaitGroup
}

func (a *Actions) now() time.Time {
	if a.Now != nil {
		return a.Now()
	}
	return time.Now()
}

func (a *Actions) stamp() string { return a.now().Local().Format("2006-01-02 15:04:05") }

// Clip saves the last ClipSeconds of the stream, like ShadowPlay's instant replay.
func (a *Actions) Clip(ctx context.Context) (string, error) {
	title := "Clip " + a.stamp()
	uuid, err := a.Backend.Clip(ctx, a.ClipSeconds, title)
	if err != nil {
		a.Notifier.Notify("Clip failed", describe(err))
		return "", err
	}
	msg := fmt.Sprintf("Saving the last %gs", a.ClipSeconds)
	a.Notifier.Notify("Clipping", msg)
	a.watch(uuid, "Clip saved", title, "Clip failed")
	return msg, nil
}

// Mark drops a marker at the current point of the stream.
func (a *Actions) Mark(ctx context.Context, message string) (string, error) {
	if message == "" {
		message = "Marker " + a.stamp()
	}
	m, err := a.Backend.CreateMarker(ctx, message)
	if err != nil {
		a.Notifier.Notify("Marker failed", describe(err))
		return "", err
	}
	a.Notifier.Notify("Marker added", m.Message)
	return "Marker added: " + m.Message, nil
}

// ToggleSection works like ShadowPlay's record toggle: the first press marks the start of a
// section, the second marks its end and saves everything in between as a VoD.
func (a *Actions) ToggleSection(ctx context.Context) (string, error) {
	a.mu.Lock()
	defer a.mu.Unlock()

	if a.sectionStart == nil {
		m, err := a.Backend.CreateMarker(ctx, "Section start "+a.stamp())
		if err != nil {
			a.Notifier.Notify("Section failed", describe(err))
			return "", err
		}
		a.sectionStart = &m
		a.Notifier.Notify("Section started", "Press again to save the section")
		return "Section started", nil
	}

	end, err := a.Backend.CreateMarker(ctx, "Section end "+a.stamp())
	if err != nil {
		a.Notifier.Notify("Section failed", describe(err))
		return "", err
	}
	start := a.sectionStart

	// Markers on different streams cannot bound one section; the stream restarted in between.
	// Treat this press as the start of a new section rather than losing it.
	if end.StreamID != start.StreamID {
		a.sectionStart = &end
		err := errors.New("the stream restarted since the section began; a new section started now")
		a.Notifier.Notify("Section restarted", err.Error())
		return "", err
	}

	title := "Section " + start.Timestamp.Local().Format("2006-01-02 15:04:05")
	uuid, err := a.Backend.SaveSectionByMarkers(ctx, start.ID, end.ID, title)
	if err != nil {
		// Keep the start so another press can retry with a new end marker.
		a.Notifier.Notify("Section failed", describe(err))
		return "", err
	}
	a.sectionStart = nil
	a.Notifier.Notify("Saving section", fmt.Sprintf("%s long", end.Timestamp.Sub(start.Timestamp).Round(time.Second)))
	a.watch(uuid, "Section saved", title, "Section failed")
	return "Saving section", nil
}

// Status describes whether a section is being recorded.
func (a *Actions) Status() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.sectionStart == nil {
		return "running; no section in progress"
	}
	return "running; section in progress since " + a.sectionStart.Timestamp.Local().Format("15:04:05")
}

// Handle dispatches a control request.
func (a *Actions) Handle(ctx context.Context, req control.Request) (string, error) {
	switch req.Action {
	case ActionClip:
		return a.Clip(ctx)
	case ActionMarker:
		return a.Mark(ctx, req.Arg)
	case ActionSection:
		return a.ToggleSection(ctx)
	case ActionStatus:
		return a.Status(), nil
	default:
		return "", fmt.Errorf("unknown action %q", req.Action)
	}
}

// Wait blocks until all job watchers have finished.
func (a *Actions) Wait() { a.jobs.Wait() }

// watch polls a media job in the background and notifies when it finishes.
func (a *Actions) watch(uuid, okTitle, okBody, failTitle string) {
	if uuid == "" {
		return
	}
	interval, timeout := a.PollInterval, a.JobTimeout
	if interval == 0 {
		interval = 2 * time.Second
	}
	if timeout == 0 {
		timeout = 10 * time.Minute
	}

	a.jobs.Add(1)
	go func() {
		defer a.jobs.Done()
		ctx, cancel := context.WithTimeout(context.Background(), timeout)
		defer cancel()

		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				a.Notifier.Notify(failTitle, "timed out waiting for the server to finish")
				return
			case <-ticker.C:
			}
			job, err := a.Backend.Job(ctx, uuid)
			if err != nil {
				continue // transient; keep polling until the timeout
			}
			switch job.State {
			case api.JobSucceeded:
				a.Notifier.Notify(okTitle, okBody)
				return
			case api.JobFailed:
				a.Notifier.Notify(failTitle, firstNonEmpty(job.ErrorOutput, "the server could not process it"))
				return
			}
		}
	}()
}

// describe turns an error into a short message for a notification.
func describe(err error) string {
	var apiErr *api.Error
	switch {
	case errors.Is(err, api.ErrNotAuthenticated):
		return "session expired; run `vod-client login`"
	case errors.As(err, &apiErr) && apiErr.Status == 409:
		return "you are not live yet (" + apiErr.Message + ")"
	default:
		return err.Error()
	}
}

func firstNonEmpty(s ...string) string {
	for _, v := range s {
		if v != "" {
			return v
		}
	}
	return ""
}
