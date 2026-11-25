// Package obstest provides a fake obs-websocket v5 server for tests.
package obstest

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync"

	"github.com/coder/websocket"
)

// Request is a request the fake received.
type Request struct {
	Type string
	Data map[string]any
}

// Server mimics the obs-websocket requests vod-client uses.
type Server struct {
	*httptest.Server
	Password string

	mu            sync.Mutex
	requests      []Request
	streaming     bool
	sceneItems    int
	inputKinds    []string
	streamService map[string]any
	conns         []*websocket.Conn
	// Fail makes the named request type fail with the given status code.
	Fail map[string]int
}

const (
	salt      = "c2FsdA=="
	challenge = "Y2hhbGxlbmdl"
)

// New starts a fake OBS on a random port. An empty password disables authentication.
func New(password string) *Server {
	s := newServer(password)
	s.Server = httptest.NewServer(http.HandlerFunc(s.serve))
	return s
}

// Listen starts a fake OBS on addr, e.g. "127.0.0.1:4455", like a real OBS would.
func Listen(addr, password string) (*Server, error) {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, err
	}
	s := newServer(password)
	s.Server = httptest.NewUnstartedServer(http.HandlerFunc(s.serve))
	s.Server.Listener.Close()
	s.Server.Listener = ln
	s.Server.Start()
	return s, nil
}

func newServer(password string) *Server {
	return &Server{
		Password:   password,
		inputKinds: []string{"pipewire-desktop-capture-source", "xshm_input_v2", "ffmpeg_source"},
		Fail:       map[string]int{},
	}
}

// URL returns the server's ws:// URL.
func (s *Server) URL() string { return "ws" + s.Server.URL[len("http"):] }

// Port returns the port the server listens on.
func (s *Server) Port() int {
	_, p, _ := net.SplitHostPort(s.Listener.Addr().String())
	n, _ := strconv.Atoi(p)
	return n
}

// Requests returns the requests received so far.
func (s *Server) Requests() []Request {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]Request(nil), s.requests...)
}

// RequestsOf returns the received requests of one type.
func (s *Server) RequestsOf(typ string) []Request {
	var out []Request
	for _, r := range s.Requests() {
		if r.Type == typ {
			out = append(out, r)
		}
	}
	return out
}

// Streaming reports whether the fake is "streaming".
func (s *Server) Streaming() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.streaming
}

// SetStreaming sets the stream state, e.g. to simulate a dropped stream.
func (s *Server) SetStreaming(v bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.streaming = v
}

// SetSceneItems sets how many sources the current scene has.
func (s *Server) SetSceneItems(n int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sceneItems = n
}

// SetInputKinds sets the input kinds OBS reports as available.
func (s *Server) SetInputKinds(kinds ...string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.inputKinds = kinds
}

// StreamService returns the last stream service settings applied.
func (s *Server) StreamService() map[string]any {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.streamService
}

// DropConnections closes every client connection, as if OBS restarted its websocket server.
func (s *Server) DropConnections() {
	s.mu.Lock()
	conns := s.conns
	s.conns = nil
	s.mu.Unlock()
	for _, c := range conns {
		c.Close(websocket.StatusGoingAway, "bye")
	}
}

func authString(password string) string {
	secret := sha256.Sum256([]byte(password + salt))
	resp := sha256.Sum256([]byte(base64.StdEncoding.EncodeToString(secret[:]) + challenge))
	return base64.StdEncoding.EncodeToString(resp[:])
}

type message struct {
	Op int             `json:"op"`
	D  json.RawMessage `json:"d"`
}

func send(ctx context.Context, c *websocket.Conn, op int, d any) error {
	payload, _ := json.Marshal(d)
	data, _ := json.Marshal(message{Op: op, D: payload})
	return c.Write(ctx, websocket.MessageText, data)
}

func (s *Server) serve(w http.ResponseWriter, r *http.Request) {
	c, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{"obswebsocket.json"}})
	if err != nil {
		return
	}
	ctx := r.Context()

	hello := map[string]any{"obsWebSocketVersion": "5.5.0", "rpcVersion": 1}
	if s.Password != "" {
		hello["authentication"] = map[string]string{"challenge": challenge, "salt": salt}
	}
	if send(ctx, c, 0, hello) != nil {
		return
	}

	_, data, err := c.Read(ctx)
	if err != nil {
		return
	}
	var msg message
	var ident struct {
		Authentication string `json:"authentication"`
	}
	if json.Unmarshal(data, &msg) != nil || msg.Op != 1 || json.Unmarshal(msg.D, &ident) != nil {
		c.Close(4007, "not identified")
		return
	}
	if s.Password != "" && ident.Authentication != authString(s.Password) {
		c.Close(4009, "Authentication failed.")
		return
	}
	if send(ctx, c, 2, map[string]any{"negotiatedRpcVersion": 1}) != nil {
		return
	}

	s.mu.Lock()
	s.conns = append(s.conns, c)
	s.mu.Unlock()

	for {
		_, data, err := c.Read(ctx)
		if err != nil {
			return
		}
		if json.Unmarshal(data, &msg) != nil || msg.Op != 6 {
			continue
		}
		var req struct {
			RequestType string         `json:"requestType"`
			RequestID   string         `json:"requestId"`
			RequestData map[string]any `json:"requestData"`
		}
		json.Unmarshal(msg.D, &req)

		// An unsolicited event first, which clients must ignore.
		send(ctx, c, 5, map[string]any{"eventType": "Noise", "eventIntent": 1})

		code, comment, out := s.handle(req.RequestType, req.RequestData)
		resp := map[string]any{
			"requestType": req.RequestType,
			"requestId":   req.RequestID,
			"requestStatus": map[string]any{
				"result": code == 100, "code": code, "comment": comment,
			},
		}
		if out != nil {
			resp["responseData"] = out
		}
		if send(ctx, c, 7, resp) != nil {
			return
		}
	}
}

func (s *Server) handle(typ string, data map[string]any) (int, string, any) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.requests = append(s.requests, Request{Type: typ, Data: data})

	if code, ok := s.Fail[typ]; ok {
		return code, "forced failure", nil
	}

	switch typ {
	case "GetStreamStatus":
		return 100, "", map[string]any{"outputActive": s.streaming}
	case "StartStream":
		if s.streaming {
			return 500, "output already running", nil
		}
		s.streaming = true
		return 100, "", nil
	case "StopStream":
		if !s.streaming {
			return 501, "output not running", nil
		}
		s.streaming = false
		return 100, "", nil
	case "SetStreamServiceSettings":
		s.streamService = data
		return 100, "", nil
	case "GetCurrentProgramScene":
		return 100, "", map[string]any{"currentProgramSceneName": "Scene", "sceneName": "Scene"}
	case "GetSceneItemList":
		items := make([]map[string]any, s.sceneItems)
		for i := range items {
			items[i] = map[string]any{"sceneItemId": i + 1}
		}
		return 100, "", map[string]any{"sceneItems": items}
	case "GetInputKindList":
		return 100, "", map[string]any{"inputKinds": s.inputKinds}
	case "CreateInput":
		s.sceneItems++
		return 100, "", map[string]any{"inputUuid": "uuid-1", "sceneItemId": s.sceneItems}
	default:
		return 204, "unknown request type", nil
	}
}
