// Package apitest provides an in-memory fake of the VoD system backend for tests.
package apitest

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"time"
)

// User is the account the fake backend serves.
var User = map[string]any{
	"id": 7, "username": "streamer", "email": "streamer@example.com", "name": "Streamer",
	"streamKey": "key-abc123",
}

// Call is a request the fake received.
type Call struct {
	Method string
	Path   string
	Body   map[string]any
}

// Backend mimics the backend's auth, stream, marker, media and job endpoints.
type Backend struct {
	*httptest.Server

	mu sync.Mutex
	// ValidIDTokens are Google ID tokens the login endpoint accepts.
	ValidIDTokens map[string]bool
	// Streaming controls whether stream-dependent endpoints succeed or return 409.
	Streaming bool
	// StreamID is the active stream's ID, stamped on markers.
	StreamID int64
	// JobStates maps job UUIDs to the state reported for them; unknown jobs have SUCCEEDED.
	JobStates map[string]string
	// FailSave makes save-by-markers fail with 500.
	FailSave bool

	access    map[string]bool
	refresh   map[string]bool
	issued    int
	calls     []Call
	markerID  int64
	jobID     int
	refreshes int
}

// New starts a fake backend. Close it when done.
func New() *Backend {
	b := &Backend{
		ValidIDTokens: map[string]bool{},
		Streaming:     true,
		StreamID:      1,
		JobStates:     map[string]string{},
		access:        map[string]bool{},
		refresh:       map[string]bool{},
	}
	b.Server = httptest.NewServer(http.HandlerFunc(b.serve))
	return b
}

// IssueRefreshToken creates a valid refresh token, as if from an earlier login.
func (b *Backend) IssueRefreshToken() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.issued++
	t := fmt.Sprintf("refresh-%d", b.issued)
	b.refresh[t] = true
	return t
}

// ExpireAccessTokens invalidates every access token, as if they had timed out.
func (b *Backend) ExpireAccessTokens() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.access = map[string]bool{}
}

// RevokeAll invalidates every token, as if the session family was revoked.
func (b *Backend) RevokeAll() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.access = map[string]bool{}
	b.refresh = map[string]bool{}
}

// RefreshCount returns how many successful refreshes the backend has served.
func (b *Backend) RefreshCount() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.refreshes
}

// Calls returns the requests received so far, excluding auth endpoints.
func (b *Backend) Calls() []Call {
	b.mu.Lock()
	defer b.mu.Unlock()
	return append([]Call(nil), b.calls...)
}

// CallsTo returns the received requests for path.
func (b *Backend) CallsTo(path string) []Call {
	var out []Call
	for _, c := range b.Calls() {
		if c.Path == path {
			out = append(out, c)
		}
	}
	return out
}

// Set mutates the backend's state under its lock.
func (b *Backend) Set(f func(b *Backend)) {
	b.mu.Lock()
	defer b.mu.Unlock()
	f(b)
}

func (b *Backend) serve(w http.ResponseWriter, r *http.Request) {
	b.mu.Lock()
	defer b.mu.Unlock()

	var body map[string]any
	if data, _ := io.ReadAll(r.Body); len(data) > 0 {
		if err := json.Unmarshal(data, &body); err != nil {
			reply(w, http.StatusBadRequest, "malformed JSON", nil)
			return
		}
	}

	switch r.URL.Path {
	case "/api/v1/users/login":
		token, _ := body["token"].(string)
		if !b.ValidIDTokens[token] {
			reply(w, http.StatusUnauthorized, "Invalid ID token", nil)
			return
		}
		b.issueSession(w)
		reply(w, http.StatusOK, "Logged in successfully", map[string]any{"accessTokenExpiresIn": 300})
		return
	case "/api/v1/users/refresh":
		ck, err := r.Cookie("refreshToken")
		if err != nil || !b.refresh[ck.Value] {
			reply(w, http.StatusUnauthorized, "Invalid refresh token", nil)
			return
		}
		delete(b.refresh, ck.Value) // rotation: each refresh token is single use
		b.refreshes++
		b.issueSession(w)
		reply(w, http.StatusOK, "Refreshed tokens successfully", map[string]any{"accessTokenExpiresIn": 300})
		return
	case "/api/v1/users/logout":
		if ck, err := r.Cookie("refreshToken"); err == nil {
			delete(b.refresh, ck.Value)
		}
		reply(w, http.StatusOK, "Logged out successfully", nil)
		return
	}

	if !b.access[strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")] {
		w.WriteHeader(http.StatusUnauthorized) // Spring's entry point sends an empty 401
		return
	}
	b.calls = append(b.calls, Call{Method: r.Method, Path: r.URL.Path, Body: body})

	switch {
	case r.Method == http.MethodGet && r.URL.Path == "/api/v1/users/me":
		reply(w, http.StatusOK, "User retrieved successfully", User)
	case r.Method == http.MethodGet && r.URL.Path == "/api/v1/stream/current":
		if b.Streaming {
			reply(w, http.StatusOK, "Streaming status retrieved", map[string]any{"isStreaming": true, "id": b.StreamID})
		} else {
			reply(w, http.StatusOK, "Streaming status retrieved", map[string]any{"isStreaming": false, "id": nil})
		}
	case r.Method == http.MethodPost && r.URL.Path == "/api/v1/media/clip":
		if !b.Streaming {
			reply(w, http.StatusConflict, "User is not streaming", nil)
			return
		}
		reply(w, http.StatusOK, "Clipping successfully started", map[string]any{"uuid": b.newJob()})
	case r.Method == http.MethodPost && r.URL.Path == "/api/v1/markers":
		if !b.Streaming {
			reply(w, http.StatusConflict, "User must be streaming to mark", nil)
			return
		}
		b.markerID++
		reply(w, http.StatusOK, "Marker created successfully", map[string]any{
			"id": b.markerID, "userId": 7, "streamId": b.StreamID, "message": body["message"],
			"timestamp": time.Now().UTC().Format(time.RFC3339Nano),
		})
	case r.Method == http.MethodPost && r.URL.Path == "/api/v1/media/save/markers":
		if b.FailSave {
			reply(w, http.StatusInternalServerError, "segments missing", nil)
			return
		}
		reply(w, http.StatusOK, "Section saving successfully started", map[string]any{"uuid": b.newJob()})
	case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/api/v1/jobs/"):
		uuid := strings.TrimPrefix(r.URL.Path, "/api/v1/jobs/")
		state := b.JobStates[uuid]
		if state == "" {
			state = "SUCCEEDED"
		}
		reply(w, http.StatusOK, "Job successfully retreived", map[string]any{
			"uuid": uuid, "progress": 1, "isComplete": state == "SUCCEEDED" || state == "FAILED",
			"state": state, "errorOutput": map[bool]string{true: "ffmpeg exploded"}[state == "FAILED"],
		})
	default:
		reply(w, http.StatusNotFound, "no such endpoint", nil)
	}
}

func (b *Backend) newJob() string {
	b.jobID++
	return fmt.Sprintf("job-%d", b.jobID)
}

func (b *Backend) issueSession(w http.ResponseWriter) {
	b.issued++
	access, refresh := fmt.Sprintf("access-%d", b.issued), fmt.Sprintf("refresh-%d", b.issued)
	b.access[access] = true
	b.refresh[refresh] = true
	http.SetCookie(w, &http.Cookie{Name: "refreshToken", Value: refresh, Path: "/api/v1/users", HttpOnly: true})
	http.SetCookie(w, &http.Cookie{Name: "accessToken", Value: access, Path: "/", HttpOnly: true})
}

func reply(w http.ResponseWriter, status int, message string, data any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	s := "success"
	if status >= 400 {
		s = "error"
	}
	json.NewEncoder(w).Encode(map[string]any{"status": s, "message": message, "data": data})
}
