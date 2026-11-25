// Package obs installs, launches and remote-controls OBS Studio through obs-websocket v5.
package obs

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"sync"
	"sync/atomic"

	"github.com/coder/websocket"
)

// obs-websocket v5 opcodes.
const (
	opHello      = 0
	opIdentify   = 1
	opIdentified = 2
	opRequest    = 6
	opResponse   = 7
)

const rpcVersion = 1

// ErrAuthFailed means OBS rejected the websocket password.
var ErrAuthFailed = errors.New("obs-websocket rejected the password")

// RequestError is a request OBS processed but refused.
type RequestError struct {
	RequestType string
	Code        int
	Comment     string
}

func (e *RequestError) Error() string {
	return fmt.Sprintf("obs %s failed (code %d): %s", e.RequestType, e.Code, e.Comment)
}

type message struct {
	Op int             `json:"op"`
	D  json.RawMessage `json:"d"`
}

type response struct {
	RequestType   string `json:"requestType"`
	RequestID     string `json:"requestId"`
	RequestStatus struct {
		Result  bool   `json:"result"`
		Code    int    `json:"code"`
		Comment string `json:"comment"`
	} `json:"requestStatus"`
	ResponseData json.RawMessage `json:"responseData"`
}

// Client is an obs-websocket v5 connection. It is safe for concurrent use.
type Client struct {
	conn   *websocket.Conn
	nextID atomic.Uint64

	mu      sync.Mutex
	pending map[string]chan response
	err     error // set once the read loop stops

	done chan struct{}
}

// Dial connects to obs-websocket at url (e.g. ws://127.0.0.1:4455) and authenticates.
func Dial(ctx context.Context, url, password string) (*Client, error) {
	conn, _, err := websocket.Dial(ctx, url, &websocket.DialOptions{Subprotocols: []string{"obswebsocket.json"}})
	if err != nil {
		return nil, err
	}
	conn.SetReadLimit(16 << 20) // screenshots and scene lists can be large

	if err := identify(ctx, conn, password); err != nil {
		conn.Close(websocket.StatusNormalClosure, "")
		return nil, err
	}

	c := &Client{conn: conn, pending: map[string]chan response{}, done: make(chan struct{})}
	go c.readLoop()
	return c, nil
}

func identify(ctx context.Context, conn *websocket.Conn, password string) error {
	var hello struct {
		RPCVersion     int `json:"rpcVersion"`
		Authentication *struct {
			Challenge string `json:"challenge"`
			Salt      string `json:"salt"`
		} `json:"authentication"`
	}
	if err := readOp(ctx, conn, opHello, &hello); err != nil {
		return fmt.Errorf("obs hello: %w", err)
	}

	ident := map[string]any{"rpcVersion": rpcVersion, "eventSubscriptions": 0}
	if hello.Authentication != nil {
		ident["authentication"] = authResponse(password, hello.Authentication.Salt, hello.Authentication.Challenge)
	}
	if err := writeOp(ctx, conn, opIdentify, ident); err != nil {
		return err
	}

	if err := readOp(ctx, conn, opIdentified, nil); err != nil {
		// OBS closes with 4009 (AuthenticationFailed) on a bad password.
		if websocket.CloseStatus(err) == 4009 {
			return ErrAuthFailed
		}
		return fmt.Errorf("obs identify: %w", err)
	}
	return nil
}

// authResponse computes base64(sha256(base64(sha256(password + salt)) + challenge)).
func authResponse(password, salt, challenge string) string {
	secret := sha256.Sum256([]byte(password + salt))
	secretB64 := base64.StdEncoding.EncodeToString(secret[:])
	resp := sha256.Sum256([]byte(secretB64 + challenge))
	return base64.StdEncoding.EncodeToString(resp[:])
}

func readOp(ctx context.Context, conn *websocket.Conn, want int, out any) error {
	_, data, err := conn.Read(ctx)
	if err != nil {
		return err
	}
	var msg message
	if err := json.Unmarshal(data, &msg); err != nil {
		return err
	}
	if msg.Op != want {
		return fmt.Errorf("expected op %d, got %d", want, msg.Op)
	}
	if out != nil {
		return json.Unmarshal(msg.D, out)
	}
	return nil
}

func writeOp(ctx context.Context, conn *websocket.Conn, op int, d any) error {
	payload, err := json.Marshal(d)
	if err != nil {
		return err
	}
	data, err := json.Marshal(message{Op: op, D: payload})
	if err != nil {
		return err
	}
	return conn.Write(ctx, websocket.MessageText, data)
}

func (c *Client) readLoop() {
	defer close(c.done)
	ctx := context.Background()
	for {
		_, data, err := c.conn.Read(ctx)
		if err != nil {
			c.mu.Lock()
			c.err = fmt.Errorf("obs connection lost: %w", err)
			for id, ch := range c.pending {
				close(ch)
				delete(c.pending, id)
			}
			c.mu.Unlock()
			return
		}
		var msg message
		if json.Unmarshal(data, &msg) != nil || msg.Op != opResponse {
			continue // events and anything unexpected are ignored
		}
		var resp response
		if json.Unmarshal(msg.D, &resp) != nil {
			continue
		}
		c.mu.Lock()
		ch, ok := c.pending[resp.RequestID]
		delete(c.pending, resp.RequestID)
		c.mu.Unlock()
		if ok {
			ch <- resp
		}
	}
}

// Request sends a request and decodes its responseData into out, which may be nil.
func (c *Client) Request(ctx context.Context, requestType string, data, out any) error {
	id := strconv.FormatUint(c.nextID.Add(1), 10)
	ch := make(chan response, 1)

	c.mu.Lock()
	if c.err != nil {
		err := c.err
		c.mu.Unlock()
		return err
	}
	c.pending[id] = ch
	c.mu.Unlock()

	req := map[string]any{"requestType": requestType, "requestId": id}
	if data != nil {
		req["requestData"] = data
	}
	if err := writeOp(ctx, c.conn, opRequest, req); err != nil {
		c.forget(id)
		return err
	}

	select {
	case resp, ok := <-ch:
		if !ok {
			c.mu.Lock()
			defer c.mu.Unlock()
			return c.err
		}
		if !resp.RequestStatus.Result {
			return &RequestError{RequestType: requestType, Code: resp.RequestStatus.Code, Comment: resp.RequestStatus.Comment}
		}
		if out != nil && len(resp.ResponseData) > 0 {
			return json.Unmarshal(resp.ResponseData, out)
		}
		return nil
	case <-ctx.Done():
		c.forget(id)
		return ctx.Err()
	}
}

func (c *Client) forget(id string) {
	c.mu.Lock()
	delete(c.pending, id)
	c.mu.Unlock()
}

// Done is closed when the connection drops.
func (c *Client) Done() <-chan struct{} { return c.done }

// Close closes the connection.
func (c *Client) Close() error {
	return c.conn.Close(websocket.StatusNormalClosure, "")
}

// SetCustomRTMP points OBS's stream output at a custom RTMP server.
func (c *Client) SetCustomRTMP(ctx context.Context, server, key string) error {
	return c.Request(ctx, "SetStreamServiceSettings", map[string]any{
		"streamServiceType": "rtmp_custom",
		"streamServiceSettings": map[string]any{
			"server":   server,
			"key":      key,
			"use_auth": false,
		},
	}, nil)
}

// StreamActive reports whether OBS is currently streaming.
func (c *Client) StreamActive(ctx context.Context) (bool, error) {
	var out struct {
		OutputActive bool `json:"outputActive"`
	}
	err := c.Request(ctx, "GetStreamStatus", nil, &out)
	return out.OutputActive, err
}

// StartStream starts streaming. Starting an active stream is not an error.
func (c *Client) StartStream(ctx context.Context) error {
	err := c.Request(ctx, "StartStream", nil, nil)
	if isCode(err, codeOutputRunning) {
		return nil
	}
	return err
}

// StopStream stops streaming. Stopping an inactive stream is not an error.
func (c *Client) StopStream(ctx context.Context) error {
	err := c.Request(ctx, "StopStream", nil, nil)
	if isCode(err, codeOutputNotRunning) {
		return nil
	}
	return err
}

// obs-websocket RequestStatus codes this package cares about.
const (
	codeOutputRunning    = 500
	codeOutputNotRunning = 501
)

func isCode(err error, code int) bool {
	var re *RequestError
	return errors.As(err, &re) && re.Code == code
}

// captureKinds are screen capture input kinds, in order of preference, across platforms.
var captureKinds = []string{
	"monitor_capture",                 // Windows
	"screen_capture",                  // macOS 13+
	"display_capture",                 // older macOS
	"pipewire-desktop-capture-source", // Linux Wayland (asks for a screen through the portal)
	"pipewire-screen-capture-source",  // older OBS name for the above
	"xshm_input_v2",                   // Linux X11
	"xshm_input",                      // older OBS name for the above
}

// EnsureCaptureSource adds a screen capture to the current scene if the scene is empty, so a
// fresh OBS install streams the desktop rather than a black frame. It returns the input kind it
// added, or "" if the scene already had sources.
func (c *Client) EnsureCaptureSource(ctx context.Context) (string, error) {
	var scene struct {
		Name string `json:"currentProgramSceneName"`
	}
	if err := c.Request(ctx, "GetCurrentProgramScene", nil, &scene); err != nil {
		return "", err
	}
	var items struct {
		SceneItems []json.RawMessage `json:"sceneItems"`
	}
	if err := c.Request(ctx, "GetSceneItemList", map[string]any{"sceneName": scene.Name}, &items); err != nil {
		return "", err
	}
	if len(items.SceneItems) > 0 {
		return "", nil
	}

	var kinds struct {
		InputKinds []string `json:"inputKinds"`
	}
	if err := c.Request(ctx, "GetInputKindList", map[string]any{"unversioned": false}, &kinds); err != nil {
		return "", err
	}
	kind := pickCaptureKind(kinds.InputKinds)
	if kind == "" {
		return "", errors.New("no screen capture source available in this OBS build")
	}
	err := c.Request(ctx, "CreateInput", map[string]any{
		"sceneName":        scene.Name,
		"inputName":        "Screen Capture (VoD Client)",
		"inputKind":        kind,
		"inputSettings":    map[string]any{},
		"sceneItemEnabled": true,
	}, nil)
	return kind, err
}

func pickCaptureKind(available []string) string {
	have := make(map[string]bool, len(available))
	for _, k := range available {
		have[k] = true
	}
	for _, k := range captureKinds {
		if have[k] {
			return k
		}
	}
	return ""
}
