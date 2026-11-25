package app

import (
	"context"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"

	"gitea.defaoite.dev/dylan/vod-system/client/internal/api"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/control"
)

// fakeBackend is an in-process Backend with scriptable behaviour.
type fakeBackend struct {
	mu        sync.Mutex
	streamID  int64
	nextID    int64
	clipErr   error
	markErr   error
	saveErr   error
	jobStates []api.JobState // returned in order, last one repeats
	clips     []float64
	titles    []string
	markers   []api.Marker
	saves     [][2]int64
}

func (f *fakeBackend) Clip(_ context.Context, d float64, title string) (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.clipErr != nil {
		return "", f.clipErr
	}
	f.clips = append(f.clips, d)
	f.titles = append(f.titles, title)
	return "job-clip", nil
}

func (f *fakeBackend) CreateMarker(_ context.Context, msg string) (api.Marker, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.markErr != nil {
		return api.Marker{}, f.markErr
	}
	f.nextID++
	m := api.Marker{ID: f.nextID, StreamID: f.streamID, Message: msg, Timestamp: time.Date(2026, 1, 1, 12, 0, int(f.nextID)*30, 0, time.UTC)}
	f.markers = append(f.markers, m)
	return m, nil
}

func (f *fakeBackend) SaveSectionByMarkers(_ context.Context, start, end int64, _ string) (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.saveErr != nil {
		return "", f.saveErr
	}
	f.saves = append(f.saves, [2]int64{start, end})
	return "job-save", nil
}

func (f *fakeBackend) Job(_ context.Context, uuid string) (api.Job, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	state := api.JobSucceeded
	if len(f.jobStates) > 0 {
		state = f.jobStates[0]
		if len(f.jobStates) > 1 {
			f.jobStates = f.jobStates[1:]
		}
	}
	return api.Job{UUID: uuid, State: state, ErrorOutput: "boom"}, nil
}

// recorder collects notifications.
type recorder struct {
	mu   sync.Mutex
	msgs []string
}

func (r *recorder) Notify(title, body string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.msgs = append(r.msgs, title+": "+body)
}

func (r *recorder) all() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]string(nil), r.msgs...)
}

func (r *recorder) has(prefix string) bool {
	for _, m := range r.all() {
		if strings.HasPrefix(m, prefix) {
			return true
		}
	}
	return false
}

func newActions(b *fakeBackend, n *recorder) *Actions {
	return &Actions{
		Backend:      b,
		Notifier:     n,
		ClipSeconds:  45,
		Now:          func() time.Time { return time.Date(2026, 11, 25, 20, 30, 0, 0, time.Local) },
		PollInterval: time.Millisecond,
		JobTimeout:   time.Second,
	}
}

func TestClip(t *testing.T) {
	b, n := &fakeBackend{streamID: 1}, &recorder{}
	a := newActions(b, n)

	msg, err := a.Clip(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	a.Wait()
	if len(b.clips) != 1 || b.clips[0] != 45 {
		t.Errorf("clips = %v, want one of 45s", b.clips)
	}
	if b.titles[0] != "Clip 2026-11-25 20:30:00" {
		t.Errorf("title = %q", b.titles[0])
	}
	if !strings.Contains(msg, "45s") {
		t.Errorf("msg = %q", msg)
	}
	if !n.has("Clipping") || !n.has("Clip saved") {
		t.Errorf("notifications = %v", n.all())
	}
}

func TestClipJobFailureIsNotified(t *testing.T) {
	b, n := &fakeBackend{jobStates: []api.JobState{api.JobProcessing, api.JobProcessing, api.JobFailed}}, &recorder{}
	a := newActions(b, n)
	if _, err := a.Clip(context.Background()); err != nil {
		t.Fatal(err)
	}
	a.Wait()
	if !n.has("Clip failed: boom") {
		t.Errorf("notifications = %v", n.all())
	}
}

func TestClipJobTimeout(t *testing.T) {
	b, n := &fakeBackend{jobStates: []api.JobState{api.JobProcessing}}, &recorder{}
	a := newActions(b, n)
	a.JobTimeout = 50 * time.Millisecond
	a.Clip(context.Background())
	a.Wait()
	if !n.has("Clip failed: timed out") {
		t.Errorf("notifications = %v", n.all())
	}
}

func TestClipNotLive(t *testing.T) {
	b, n := &fakeBackend{clipErr: &api.Error{Status: 409, Message: "User is not streaming"}}, &recorder{}
	a := newActions(b, n)
	if _, err := a.Clip(context.Background()); err == nil {
		t.Fatal("expected an error")
	}
	if !n.has("Clip failed: you are not live yet") {
		t.Errorf("notifications = %v", n.all())
	}
}

func TestSessionExpiredMessage(t *testing.T) {
	b, n := &fakeBackend{clipErr: api.ErrNotAuthenticated}, &recorder{}
	newActions(b, n).Clip(context.Background())
	if !n.has("Clip failed: session expired; run `vod-client login`") {
		t.Errorf("notifications = %v", n.all())
	}
}

func TestMark(t *testing.T) {
	b, n := &fakeBackend{streamID: 1}, &recorder{}
	a := newActions(b, n)

	if _, err := a.Mark(context.Background(), "boss fight"); err != nil {
		t.Fatal(err)
	}
	if _, err := a.Mark(context.Background(), ""); err != nil {
		t.Fatal(err)
	}
	if b.markers[0].Message != "boss fight" {
		t.Errorf("message = %q", b.markers[0].Message)
	}
	if b.markers[1].Message != "Marker 2026-11-25 20:30:00" {
		t.Errorf("default message = %q", b.markers[1].Message)
	}
	if !n.has("Marker added: boss fight") {
		t.Errorf("notifications = %v", n.all())
	}
}

func TestToggleSection(t *testing.T) {
	b, n := &fakeBackend{streamID: 1}, &recorder{}
	a := newActions(b, n)
	ctx := context.Background()

	if msg, err := a.ToggleSection(ctx); err != nil || msg != "Section started" {
		t.Fatalf("first press: %q, %v", msg, err)
	}
	if !strings.Contains(a.Status(), "in progress since") {
		t.Errorf("status = %q", a.Status())
	}
	if msg, err := a.ToggleSection(ctx); err != nil || msg != "Saving section" {
		t.Fatalf("second press: %q, %v", msg, err)
	}
	a.Wait()

	if len(b.saves) != 1 || b.saves[0] != [2]int64{1, 2} {
		t.Errorf("saves = %v, want markers 1→2", b.saves)
	}
	if !n.has("Saving section: 30s long") || !n.has("Section saved") {
		t.Errorf("notifications = %v", n.all())
	}
	if strings.Contains(a.Status(), "in progress since") {
		t.Error("section should be finished")
	}

	// A third press starts a fresh section.
	a.ToggleSection(ctx)
	if !strings.Contains(a.Status(), "in progress since") {
		t.Error("third press should start a new section")
	}
}

func TestToggleSectionSaveFailureKeepsStart(t *testing.T) {
	b, n := &fakeBackend{streamID: 1, saveErr: errors.New("disk full")}, &recorder{}
	a := newActions(b, n)
	ctx := context.Background()

	a.ToggleSection(ctx)
	if _, err := a.ToggleSection(ctx); err == nil {
		t.Fatal("expected the save error")
	}
	b.saveErr = nil
	if _, err := a.ToggleSection(ctx); err != nil {
		t.Fatal(err)
	}
	// The retry keeps the original start marker and uses the newest end.
	if len(b.saves) != 1 || b.saves[0] != [2]int64{1, 3} {
		t.Errorf("saves = %v, want markers 1→3", b.saves)
	}
}

func TestToggleSectionStreamRestarted(t *testing.T) {
	b, n := &fakeBackend{streamID: 1}, &recorder{}
	a := newActions(b, n)
	ctx := context.Background()

	a.ToggleSection(ctx)
	b.streamID = 2 // stream dropped and came back
	if _, err := a.ToggleSection(ctx); err == nil {
		t.Fatal("markers on different streams cannot form a section")
	}
	if len(b.saves) != 0 {
		t.Fatal("nothing should be saved")
	}
	if !n.has("Section restarted") {
		t.Errorf("notifications = %v", n.all())
	}

	// The failed press became the new start.
	a.ToggleSection(ctx)
	if len(b.saves) != 1 || b.saves[0] != [2]int64{2, 3} {
		t.Errorf("saves = %v, want markers 2→3", b.saves)
	}
}

func TestToggleSectionStartFailure(t *testing.T) {
	b, n := &fakeBackend{markErr: &api.Error{Status: 409, Message: "User must be streaming to mark"}}, &recorder{}
	a := newActions(b, n)
	if _, err := a.ToggleSection(context.Background()); err == nil {
		t.Fatal("expected an error")
	}
	if strings.Contains(a.Status(), "in progress since") {
		t.Error("a failed start must not leave a section in progress")
	}
}

func TestHandleDispatch(t *testing.T) {
	b, n := &fakeBackend{streamID: 1}, &recorder{}
	a := newActions(b, n)
	ctx := context.Background()

	for _, req := range []control.Request{{Action: ActionClip}, {Action: ActionMarker, Arg: "x"}, {Action: ActionSection}} {
		if _, err := a.Handle(ctx, req); err != nil {
			t.Errorf("%s: %v", req.Action, err)
		}
	}
	a.Wait()
	if len(b.clips) != 1 || len(b.markers) != 2 {
		t.Errorf("clips %d, markers %d", len(b.clips), len(b.markers))
	}
	if msg, err := a.Handle(ctx, control.Request{Action: ActionStatus}); err != nil || !strings.HasPrefix(msg, "running") {
		t.Errorf("status: %q, %v", msg, err)
	}
	if _, err := a.Handle(ctx, control.Request{Action: "explode"}); err == nil {
		t.Error("unknown action should fail")
	}
}

func TestConcurrentSectionPressesAreSerialised(t *testing.T) {
	b, n := &fakeBackend{streamID: 1}, &recorder{}
	a := newActions(b, n)

	var wg sync.WaitGroup
	for range 10 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			a.ToggleSection(context.Background())
		}()
	}
	wg.Wait()
	a.Wait()
	// Ten presses: five start/stop pairs, each saving consecutive markers.
	if len(b.saves) != 5 {
		t.Fatalf("saves = %v, want 5", b.saves)
	}
	for _, s := range b.saves {
		if s[1] != s[0]+1 {
			t.Errorf("save %v does not pair adjacent markers", s)
		}
	}
}
