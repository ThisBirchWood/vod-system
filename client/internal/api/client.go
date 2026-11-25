// Package api is a client for the VoD system backend REST API.
package api

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"

	"gitea.defaoite.dev/dylan/vod-system/client/internal/config"
)

const (
	accessCookie  = "accessToken"
	refreshCookie = "refreshToken"
)

// ErrNotAuthenticated means there is no valid session; the user has to log in again.
var ErrNotAuthenticated = errors.New("not authenticated")

// Error is a non-2xx response from the backend.
type Error struct {
	Status  int
	Message string
}

func (e *Error) Error() string {
	if e.Message == "" {
		return fmt.Sprintf("backend returned %d", e.Status)
	}
	return e.Message
}

// SessionStore persists the refresh token between runs.
type SessionStore interface {
	Load() (config.Session, error)
	Save(config.Session) error
	Clear() error
}

// Client talks to the backend. It authenticates with a bearer access token and transparently
// rotates it with the refresh token when it expires. It is safe for concurrent use.
type Client struct {
	baseURL string
	http    *http.Client
	store   SessionStore

	mu           sync.Mutex
	accessToken  string
	refreshToken string

	// refreshMu serialises refreshes so a burst of 401s only rotates the refresh token once;
	// the backend's rotation would otherwise treat the second use as token reuse.
	refreshMu sync.Mutex
}

// New returns a client for the backend at baseURL, restoring any session from store that was
// issued by that same backend.
func New(baseURL string, store SessionStore) (*Client, error) {
	c := &Client{
		baseURL: strings.TrimRight(baseURL, "/"),
		http:    &http.Client{Timeout: 30 * time.Second},
		store:   store,
	}
	sess, err := store.Load()
	if err != nil {
		return nil, fmt.Errorf("load session: %w", err)
	}
	if sess.ServerURL == c.baseURL {
		c.refreshToken = sess.RefreshToken
	}
	return c, nil
}

// HasSession reports whether a refresh token is available to resume a session without logging in.
func (c *Client) HasSession() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.refreshToken != ""
}

// Login exchanges a Google ID token for a backend session.
func (c *Client) Login(ctx context.Context, googleIDToken string) error {
	body, _ := json.Marshal(map[string]string{"token": googleIDToken})
	resp, err := c.send(ctx, http.MethodPost, "/api/v1/users/login", body, nil)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("login rejected: %w", readError(resp))
	}
	return c.adoptCookies(resp)
}

// Refresh exchanges the refresh token for a new access and refresh token pair.
func (c *Client) Refresh(ctx context.Context) error {
	c.mu.Lock()
	rt := c.refreshToken
	c.mu.Unlock()
	if rt == "" {
		return ErrNotAuthenticated
	}

	resp, err := c.send(ctx, http.MethodPost, "/api/v1/users/refresh", nil, &http.Cookie{Name: refreshCookie, Value: rt})
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusUnauthorized {
		c.dropSession()
		return ErrNotAuthenticated
	}
	if resp.StatusCode != http.StatusOK {
		return readError(resp)
	}
	return c.adoptCookies(resp)
}

// Logout revokes the session server side and forgets it locally.
func (c *Client) Logout(ctx context.Context) error {
	c.mu.Lock()
	rt := c.refreshToken
	c.mu.Unlock()

	var err error
	if rt != "" {
		var resp *http.Response
		resp, err = c.send(ctx, http.MethodPost, "/api/v1/users/logout", nil, &http.Cookie{Name: refreshCookie, Value: rt})
		if err == nil {
			resp.Body.Close()
		}
	}
	c.dropSession()
	return err
}

// User is the authenticated user's profile.
type User struct {
	ID        int64  `json:"id"`
	Username  string `json:"username"`
	Email     string `json:"email"`
	Name      string `json:"name"`
	StreamKey string `json:"streamKey"`
}

// Me returns the authenticated user, including their RTMP stream key.
func (c *Client) Me(ctx context.Context) (User, error) {
	var u User
	err := c.do(ctx, http.MethodGet, "/api/v1/users/me", nil, &u)
	return u, err
}

// StreamStatus reports whether the backend has registered a live stream for the user.
type StreamStatus struct {
	IsStreaming bool   `json:"isStreaming"`
	ID          *int64 `json:"id"`
}

// CurrentStream returns the user's live status.
func (c *Client) CurrentStream(ctx context.Context) (StreamStatus, error) {
	var s StreamStatus
	err := c.do(ctx, http.MethodGet, "/api/v1/stream/current", nil, &s)
	return s, err
}

// Clip saves the last duration seconds of the live stream as a clip and returns the job UUID.
func (c *Client) Clip(ctx context.Context, duration float64, title string) (string, error) {
	req := map[string]any{"duration": duration}
	if title != "" {
		req["title"] = title
	}
	var out struct {
		UUID string `json:"uuid"`
	}
	err := c.do(ctx, http.MethodPost, "/api/v1/media/clip", req, &out)
	return out.UUID, err
}

// Marker is a timestamped point on a stream.
type Marker struct {
	ID        int64     `json:"id"`
	StreamID  int64     `json:"streamId"`
	Message   string    `json:"message"`
	Timestamp time.Time `json:"timestamp"`
}

// CreateMarker drops a marker at the current moment of the live stream.
func (c *Client) CreateMarker(ctx context.Context, message string) (Marker, error) {
	var m Marker
	err := c.do(ctx, http.MethodPost, "/api/v1/markers", map[string]string{"message": message}, &m)
	return m, err
}

// SaveSectionByMarkers saves the stream between two markers as a VoD and returns the job UUID.
func (c *Client) SaveSectionByMarkers(ctx context.Context, startMarkerID, endMarkerID int64, title string) (string, error) {
	req := map[string]any{"startMarkerId": startMarkerID, "endMarkerId": endMarkerID}
	if title != "" {
		req["title"] = title
	}
	var out struct {
		UUID string `json:"uuid"`
	}
	err := c.do(ctx, http.MethodPost, "/api/v1/media/save/markers", req, &out)
	return out.UUID, err
}

// JobState mirrors the backend's JobState enum.
type JobState string

const (
	JobReady      JobState = "READY"
	JobProcessing JobState = "PROCESSING"
	JobSucceeded  JobState = "SUCCEEDED"
	JobFailed     JobState = "FAILED"
)

// Job is the progress of an asynchronous media job.
type Job struct {
	UUID        string   `json:"uuid"`
	Progress    float64  `json:"progress"`
	IsComplete  bool     `json:"isComplete"`
	State       JobState `json:"state"`
	ErrorOutput string   `json:"errorOutput"`
}

// Job returns the current state of a media job.
func (c *Client) Job(ctx context.Context, uuid string) (Job, error) {
	var j Job
	err := c.do(ctx, http.MethodGet, "/api/v1/jobs/"+uuid, nil, &j)
	return j, err
}

// do performs an authenticated JSON request and decodes the envelope's data into out. An expired
// access token is refreshed and the request retried once.
func (c *Client) do(ctx context.Context, method, path string, in, out any) error {
	var body []byte
	if in != nil {
		var err error
		if body, err = json.Marshal(in); err != nil {
			return err
		}
	}

	c.mu.Lock()
	token := c.accessToken
	c.mu.Unlock()

	// No access token yet (fresh start from a stored refresh token): get one before trying.
	if token == "" {
		if err := c.refreshIfStale(ctx, token); err != nil {
			return err
		}
		c.mu.Lock()
		token = c.accessToken
		c.mu.Unlock()
	}

	resp, err := c.sendAuthed(ctx, method, path, body, token)
	if err != nil {
		return err
	}
	if resp.StatusCode == http.StatusUnauthorized {
		resp.Body.Close()
		if err := c.refreshIfStale(ctx, token); err != nil {
			return err
		}
		c.mu.Lock()
		token = c.accessToken
		c.mu.Unlock()
		if resp, err = c.sendAuthed(ctx, method, path, body, token); err != nil {
			return err
		}
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusUnauthorized {
		return ErrNotAuthenticated
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return readError(resp)
	}
	if out == nil {
		return nil
	}

	var envelope struct {
		Data json.RawMessage `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&envelope); err != nil {
		return fmt.Errorf("decode %s response: %w", path, err)
	}
	if len(envelope.Data) == 0 || string(envelope.Data) == "null" {
		return nil
	}
	return json.Unmarshal(envelope.Data, out)
}

// refreshIfStale refreshes the session unless another goroutine already replaced staleToken.
func (c *Client) refreshIfStale(ctx context.Context, staleToken string) error {
	c.refreshMu.Lock()
	defer c.refreshMu.Unlock()

	c.mu.Lock()
	current := c.accessToken
	c.mu.Unlock()
	if current != "" && current != staleToken {
		return nil
	}
	return c.Refresh(ctx)
}

func (c *Client) sendAuthed(ctx context.Context, method, path string, body []byte, token string) (*http.Response, error) {
	req, err := c.newRequest(ctx, method, path, body)
	if err != nil {
		return nil, err
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	return c.http.Do(req)
}

func (c *Client) send(ctx context.Context, method, path string, body []byte, cookie *http.Cookie) (*http.Response, error) {
	req, err := c.newRequest(ctx, method, path, body)
	if err != nil {
		return nil, err
	}
	if cookie != nil {
		req.AddCookie(cookie)
	}
	return c.http.Do(req)
}

func (c *Client) newRequest(ctx context.Context, method, path string, body []byte) (*http.Request, error) {
	var r io.Reader
	if body != nil {
		r = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.baseURL+path, r)
	if err != nil {
		return nil, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("Accept", "application/json")
	return req, nil
}

// adoptCookies stores the tokens the backend set as cookies and persists the refresh token.
func (c *Client) adoptCookies(resp *http.Response) error {
	var access, refresh string
	for _, ck := range resp.Cookies() {
		switch ck.Name {
		case accessCookie:
			access = ck.Value
		case refreshCookie:
			refresh = ck.Value
		}
	}
	if access == "" || refresh == "" {
		return errors.New("backend did not return session cookies")
	}

	c.mu.Lock()
	c.accessToken = access
	c.refreshToken = refresh
	c.mu.Unlock()

	return c.store.Save(config.Session{ServerURL: c.baseURL, RefreshToken: refresh})
}

func (c *Client) dropSession() {
	c.mu.Lock()
	c.accessToken = ""
	c.refreshToken = ""
	c.mu.Unlock()
	_ = c.store.Clear()
}

// readError builds an Error from a failed response, preferring the envelope's message.
func readError(resp *http.Response) error {
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	var envelope struct {
		Message string `json:"message"`
	}
	msg := strings.TrimSpace(string(data))
	if json.Unmarshal(data, &envelope) == nil && envelope.Message != "" {
		msg = envelope.Message
	}
	return &Error{Status: resp.StatusCode, Message: msg}
}
