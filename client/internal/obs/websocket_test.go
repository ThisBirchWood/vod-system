package obs_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"gitea.defaoite.dev/dylan/vod-system/client/internal/obs"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/obs/obstest"
)

func dial(t *testing.T, s *obstest.Server, password string) *obs.Client {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, err := obs.Dial(ctx, s.URL(), password)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	t.Cleanup(func() { c.Close() })
	return c
}

func TestDialWithPassword(t *testing.T) {
	s := obstest.New("hunter2")
	defer s.Close()
	c := dial(t, s, "hunter2")
	if _, err := c.StreamActive(context.Background()); err != nil {
		t.Fatalf("request after auth: %v", err)
	}
}

func TestDialWithoutAuth(t *testing.T) {
	s := obstest.New("")
	defer s.Close()
	dial(t, s, "ignored")
}

func TestDialWrongPassword(t *testing.T) {
	s := obstest.New("hunter2")
	defer s.Close()
	_, err := obs.Dial(context.Background(), s.URL(), "wrong")
	if !errors.Is(err, obs.ErrAuthFailed) {
		t.Fatalf("got %v, want ErrAuthFailed", err)
	}
}

func TestConnectDoesNotRetryBadPassword(t *testing.T) {
	s := obstest.New("hunter2")
	defer s.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	start := time.Now()
	_, err := obs.Connect(ctx, s.Port(), "wrong")
	if !errors.Is(err, obs.ErrAuthFailed) {
		t.Fatalf("got %v, want ErrAuthFailed", err)
	}
	if time.Since(start) > 3*time.Second {
		t.Error("a rejected password should fail fast instead of retrying")
	}
}

func TestConnectGivesUpWhenNothingListens(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 1200*time.Millisecond)
	defer cancel()
	if _, err := obs.Connect(ctx, 1, "pw"); err == nil {
		t.Fatal("expected an error")
	}
}

func TestSetCustomRTMP(t *testing.T) {
	s := obstest.New("pw")
	defer s.Close()
	c := dial(t, s, "pw")

	if err := c.SetCustomRTMP(context.Background(), "rtmp://vod.example.com:1935/live", "key-1"); err != nil {
		t.Fatal(err)
	}
	got := s.StreamService()
	if got["streamServiceType"] != "rtmp_custom" {
		t.Errorf("service type = %v", got["streamServiceType"])
	}
	settings, _ := got["streamServiceSettings"].(map[string]any)
	if settings["server"] != "rtmp://vod.example.com:1935/live" || settings["key"] != "key-1" {
		t.Errorf("settings = %v", settings)
	}
}

func TestStartStopStreamIdempotent(t *testing.T) {
	s := obstest.New("pw")
	defer s.Close()
	c := dial(t, s, "pw")
	ctx := context.Background()

	for i := range 2 {
		if err := c.StartStream(ctx); err != nil {
			t.Fatalf("start #%d: %v", i+1, err)
		}
	}
	if active, _ := c.StreamActive(ctx); !active {
		t.Error("stream should be active")
	}
	for i := range 2 {
		if err := c.StopStream(ctx); err != nil {
			t.Fatalf("stop #%d: %v", i+1, err)
		}
	}
	if active, _ := c.StreamActive(ctx); active {
		t.Error("stream should be stopped")
	}
}

func TestRequestError(t *testing.T) {
	s := obstest.New("pw")
	defer s.Close()
	s.Fail["StartStream"] = 702
	c := dial(t, s, "pw")

	err := c.StartStream(context.Background())
	var re *obs.RequestError
	if !errors.As(err, &re) || re.Code != 702 || re.RequestType != "StartStream" {
		t.Fatalf("got %v, want RequestError 702", err)
	}
}

func TestConcurrentRequests(t *testing.T) {
	s := obstest.New("pw")
	defer s.Close()
	c := dial(t, s, "pw")

	errs := make(chan error, 20)
	for range 20 {
		go func() {
			_, err := c.StreamActive(context.Background())
			errs <- err
		}()
	}
	for range 20 {
		if err := <-errs; err != nil {
			t.Fatal(err)
		}
	}
}

func TestDroppedConnection(t *testing.T) {
	s := obstest.New("pw")
	defer s.Close()
	c := dial(t, s, "pw")

	s.DropConnections()
	select {
	case <-c.Done():
	case <-time.After(5 * time.Second):
		t.Fatal("Done not closed after the server dropped the connection")
	}
	if _, err := c.StreamActive(context.Background()); err == nil {
		t.Fatal("requests on a dead connection should fail")
	}
}

func TestEnsureCaptureSource(t *testing.T) {
	t.Run("empty scene gets a capture", func(t *testing.T) {
		s := obstest.New("pw")
		defer s.Close()
		s.SetInputKinds("ffmpeg_source", "xshm_input_v2", "pipewire-desktop-capture-source")
		c := dial(t, s, "pw")

		kind, err := c.EnsureCaptureSource(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		// PipeWire is preferred over X11 capture since it also works on Wayland.
		if kind != "pipewire-desktop-capture-source" {
			t.Errorf("kind = %q", kind)
		}
		created := s.RequestsOf("CreateInput")
		if len(created) != 1 || created[0].Data["sceneName"] != "Scene" || created[0].Data["inputKind"] != kind {
			t.Fatalf("CreateInput = %v", created)
		}
	})

	t.Run("populated scene is left alone", func(t *testing.T) {
		s := obstest.New("pw")
		defer s.Close()
		s.SetSceneItems(2)
		c := dial(t, s, "pw")

		kind, err := c.EnsureCaptureSource(context.Background())
		if err != nil || kind != "" {
			t.Fatalf("got %q, %v; want no change", kind, err)
		}
		if len(s.RequestsOf("CreateInput")) != 0 {
			t.Error("must not add sources to a scene the user set up")
		}
	})

	t.Run("no capture kind available", func(t *testing.T) {
		s := obstest.New("pw")
		defer s.Close()
		s.SetInputKinds("ffmpeg_source")
		c := dial(t, s, "pw")
		if _, err := c.EnsureCaptureSource(context.Background()); err == nil {
			t.Fatal("expected an error")
		}
	})
}
