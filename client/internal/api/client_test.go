package api_test

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"gitea.defaoite.dev/dylan/vod-system/client/internal/api"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/api/apitest"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/config"
)

func newStore(t *testing.T) *config.SessionStore {
	return config.NewSessionStore(filepath.Join(t.TempDir(), "session.json"))
}

func loggedIn(t *testing.T, b *apitest.Backend) (*api.Client, *config.SessionStore) {
	t.Helper()
	store := newStore(t)
	c, err := api.New(b.URL, store)
	if err != nil {
		t.Fatal(err)
	}
	b.Set(func(b *apitest.Backend) { b.ValidIDTokens["google-id"] = true })
	if err := c.Login(context.Background(), "google-id"); err != nil {
		t.Fatalf("login: %v", err)
	}
	return c, store
}

func TestLoginPersistsRefreshToken(t *testing.T) {
	b := apitest.New()
	defer b.Close()
	c, store := loggedIn(t, b)

	sess, _ := store.Load()
	if sess.RefreshToken == "" || sess.ServerURL != b.URL {
		t.Fatalf("session not persisted: %+v", sess)
	}
	if !c.HasSession() {
		t.Error("HasSession should be true after login")
	}

	u, err := c.Me(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if u.Username != "streamer" || u.StreamKey != "key-abc123" {
		t.Errorf("unexpected user %+v", u)
	}
}

func TestLoginRejected(t *testing.T) {
	b := apitest.New()
	defer b.Close()
	c, _ := api.New(b.URL, newStore(t))

	err := c.Login(context.Background(), "forged")
	var apiErr *api.Error
	if !errors.As(err, &apiErr) || apiErr.Status != 401 || apiErr.Message != "Invalid ID token" {
		t.Fatalf("got %v, want 401 Invalid ID token", err)
	}
}

func TestResumesStoredSession(t *testing.T) {
	b := apitest.New()
	defer b.Close()
	store := newStore(t)
	store.Save(config.Session{ServerURL: b.URL, RefreshToken: b.IssueRefreshToken()})

	c, _ := api.New(b.URL, store)
	if !c.HasSession() {
		t.Fatal("stored session should be restored")
	}
	if _, err := c.Me(context.Background()); err != nil {
		t.Fatalf("Me with only a refresh token: %v", err)
	}
	if b.RefreshCount() != 1 {
		t.Errorf("refreshes = %d, want 1", b.RefreshCount())
	}
}

func TestIgnoresSessionFromAnotherServer(t *testing.T) {
	store := newStore(t)
	store.Save(config.Session{ServerURL: "https://other.example.com", RefreshToken: "rt"})
	c, _ := api.New("https://vod.example.com", store)
	if c.HasSession() {
		t.Error("a refresh token for a different server must not be used")
	}
}

func TestExpiredAccessTokenRefreshesAndRetries(t *testing.T) {
	b := apitest.New()
	defer b.Close()
	c, store := loggedIn(t, b)
	before, _ := store.Load()

	b.ExpireAccessTokens()
	if _, err := c.Me(context.Background()); err != nil {
		t.Fatalf("Me after expiry: %v", err)
	}
	after, _ := store.Load()
	if after.RefreshToken == before.RefreshToken {
		t.Error("rotated refresh token was not persisted")
	}
}

func TestConcurrentExpiryRefreshesOnce(t *testing.T) {
	b := apitest.New()
	defer b.Close()
	c, _ := loggedIn(t, b)
	b.ExpireAccessTokens()

	var wg sync.WaitGroup
	errs := make(chan error, 10)
	for range 10 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := c.Me(context.Background())
			errs <- err
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		if err != nil {
			t.Errorf("concurrent Me: %v", err)
		}
	}
	// A second refresh with the rotated-away token would be rejected, so exactly one must happen.
	if n := b.RefreshCount(); n != 1 {
		t.Errorf("refreshes = %d, want 1", n)
	}
}

func TestRevokedSessionIsNotAuthenticated(t *testing.T) {
	b := apitest.New()
	defer b.Close()
	c, store := loggedIn(t, b)

	b.RevokeAll()
	_, err := c.Me(context.Background())
	if !errors.Is(err, api.ErrNotAuthenticated) {
		t.Fatalf("got %v, want ErrNotAuthenticated", err)
	}
	if c.HasSession() {
		t.Error("session should be dropped")
	}
	if sess, _ := store.Load(); sess.RefreshToken != "" {
		t.Error("dead refresh token should be removed from disk")
	}
}

func TestNoSessionIsNotAuthenticated(t *testing.T) {
	b := apitest.New()
	defer b.Close()
	c, _ := api.New(b.URL, newStore(t))
	if _, err := c.Me(context.Background()); !errors.Is(err, api.ErrNotAuthenticated) {
		t.Fatalf("got %v, want ErrNotAuthenticated", err)
	}
}

func TestClipRequest(t *testing.T) {
	b := apitest.New()
	defer b.Close()
	c, _ := loggedIn(t, b)

	uuid, err := c.Clip(context.Background(), 45, "Clip 1")
	if err != nil {
		t.Fatal(err)
	}
	if uuid == "" {
		t.Error("expected a job uuid")
	}
	calls := b.CallsTo("/api/v1/media/clip")
	if len(calls) != 1 {
		t.Fatalf("clip calls = %d", len(calls))
	}
	if calls[0].Body["duration"] != 45.0 || calls[0].Body["title"] != "Clip 1" {
		t.Errorf("clip body = %v", calls[0].Body)
	}
}

func TestErrorMessageFromEnvelope(t *testing.T) {
	b := apitest.New()
	defer b.Close()
	c, _ := loggedIn(t, b)
	b.Set(func(b *apitest.Backend) { b.Streaming = false })

	_, err := c.Clip(context.Background(), 30, "")
	var apiErr *api.Error
	if !errors.As(err, &apiErr) || apiErr.Status != 409 || apiErr.Message != "User is not streaming" {
		t.Fatalf("got %#v", err)
	}
}

func TestMarkersAndSectionSave(t *testing.T) {
	b := apitest.New()
	defer b.Close()
	c, _ := loggedIn(t, b)
	b.Set(func(b *apitest.Backend) { b.StreamID = 42 })
	ctx := context.Background()

	start, err := c.CreateMarker(ctx, "start")
	if err != nil {
		t.Fatal(err)
	}
	end, err := c.CreateMarker(ctx, "end")
	if err != nil {
		t.Fatal(err)
	}
	if start.StreamID != 42 || start.Message != "start" || start.Timestamp.IsZero() {
		t.Errorf("marker not decoded: %+v", start)
	}
	if _, err := c.SaveSectionByMarkers(ctx, start.ID, end.ID, "Section"); err != nil {
		t.Fatal(err)
	}
	body := b.CallsTo("/api/v1/media/save/markers")[0].Body
	if body["startMarkerId"] != float64(start.ID) || body["endMarkerId"] != float64(end.ID) || body["title"] != "Section" {
		t.Errorf("save body = %v", body)
	}
}

func TestJobAndStreamStatus(t *testing.T) {
	b := apitest.New()
	defer b.Close()
	c, _ := loggedIn(t, b)
	b.Set(func(b *apitest.Backend) { b.JobStates["job-9"] = "FAILED" })
	ctx := context.Background()

	job, err := c.Job(ctx, "job-9")
	if err != nil {
		t.Fatal(err)
	}
	if job.State != api.JobFailed || job.ErrorOutput == "" {
		t.Errorf("job = %+v", job)
	}

	st, err := c.CurrentStream(ctx)
	if err != nil || !st.IsStreaming || st.ID == nil || *st.ID != 1 {
		t.Errorf("stream status = %+v, %v", st, err)
	}
}

func TestLogoutClearsSession(t *testing.T) {
	b := apitest.New()
	defer b.Close()
	c, store := loggedIn(t, b)
	before, _ := store.Load()

	if err := c.Logout(context.Background()); err != nil {
		t.Fatal(err)
	}
	if c.HasSession() {
		t.Error("session should be gone")
	}
	if sess, _ := store.Load(); sess.RefreshToken != "" {
		t.Error("session file should be cleared")
	}

	// The server revoked it too: reusing the old token fails.
	store.Save(before)
	c2, _ := api.New(b.URL, store)
	if _, err := c2.Me(context.Background()); !errors.Is(err, api.ErrNotAuthenticated) {
		t.Errorf("revoked token still worked: %v", err)
	}
}

func TestClientConfig(t *testing.T) {
	b := apitest.New()
	defer b.Close()
	b.Set(func(b *apitest.Backend) { b.RTMPURL = "rtmp://ingest.test/live" })
	c, _ := api.New(b.URL, newStore(t))

	cfg, err := c.ClientConfig(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if cfg.GoogleClientID != apitest.GoogleClientID || cfg.RTMPURL != "rtmp://ingest.test/live" || cfg.WebsiteURL != apitest.WebsiteURL {
		t.Errorf("config = %+v", cfg)
	}
}

func TestClientConfigFromWrongServer(t *testing.T) {
	notVod := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("<html>hello</html>"))
	}))
	defer notVod.Close()
	c, _ := api.New(notVod.URL, newStore(t))
	if _, err := c.ClientConfig(context.Background()); err == nil || !strings.Contains(err.Error(), "VoD server") {
		t.Fatalf("got %v, want a friendly not-a-VoD-server error", err)
	}
}
