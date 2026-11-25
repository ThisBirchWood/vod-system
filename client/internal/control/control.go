// Package control lets other invocations of vod-client trigger actions in the running instance
// over a private Unix socket. Binding "vod-client clip" to a desktop shortcut is how hotkeys work
// on Wayland, where applications cannot grab keys globally.
package control

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Request is one command sent to the running instance.
type Request struct {
	Action string `json:"action"`
	Arg    string `json:"arg,omitempty"`
}

// Response is the result of a Request.
type Response struct {
	OK      bool   `json:"ok"`
	Message string `json:"message"`
}

// Handler performs an action and returns a message describing the result.
type Handler func(ctx context.Context, req Request) (string, error)

// ErrNotRunning means no vod-client instance is listening.
var ErrNotRunning = errors.New("vod-client is not running (start it with `vod-client run`)")

// SocketPath returns the default control socket location.
func SocketPath() (string, error) {
	if dir := os.Getenv("XDG_RUNTIME_DIR"); dir != "" {
		return filepath.Join(dir, "vod-client.sock"), nil
	}
	dir, err := os.UserCacheDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "vod-client", "control.sock"), nil
}

// Server accepts control connections.
type Server struct {
	ln   net.Listener
	path string
}

// Listen creates the control socket at path. A stale socket from a crashed instance is replaced,
// but a live one means another instance is running and is an error.
func Listen(path string) (*Server, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, err
	}
	if conn, err := net.DialTimeout("unix", path, time.Second); err == nil {
		conn.Close()
		return nil, errors.New("another vod-client instance is already running")
	}
	_ = os.Remove(path)

	ln, err := net.Listen("unix", path)
	if err != nil {
		return nil, fmt.Errorf("listen on %s: %w", path, err)
	}
	// Only this user may trigger actions on their stream.
	if err := os.Chmod(path, 0o600); err != nil {
		ln.Close()
		return nil, err
	}
	return &Server{ln: ln, path: path}, nil
}

// Serve handles connections until the server is closed.
func (s *Server) Serve(ctx context.Context, h Handler) {
	for {
		conn, err := s.ln.Accept()
		if err != nil {
			return
		}
		go handle(ctx, conn, h)
	}
}

func handle(ctx context.Context, conn net.Conn, h Handler) {
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(time.Minute))

	var req Request
	resp := Response{}
	if err := json.NewDecoder(bufio.NewReader(conn)).Decode(&req); err != nil {
		resp.Message = "bad request: " + err.Error()
	} else if msg, err := h(ctx, req); err != nil {
		resp.Message = err.Error()
	} else {
		resp.OK, resp.Message = true, msg
	}
	json.NewEncoder(conn).Encode(resp)
}

// Close stops accepting connections and removes the socket.
func (s *Server) Close() error {
	err := s.ln.Close()
	os.Remove(s.path)
	return err
}

// Send delivers req to the instance listening on path and returns its response message. A
// failed action is returned as an error carrying the instance's message.
func Send(ctx context.Context, path string, req Request) (string, error) {
	var d net.Dialer
	conn, err := d.DialContext(ctx, "unix", path)
	if err != nil {
		return "", ErrNotRunning
	}
	defer conn.Close()
	if deadline, ok := ctx.Deadline(); ok {
		conn.SetDeadline(deadline)
	}

	if err := json.NewEncoder(conn).Encode(req); err != nil {
		return "", err
	}
	var resp Response
	if err := json.NewDecoder(conn).Decode(&resp); err != nil {
		return "", fmt.Errorf("read response: %w", err)
	}
	if !resp.OK {
		return "", errors.New(strings.TrimSpace(resp.Message))
	}
	return resp.Message, nil
}
