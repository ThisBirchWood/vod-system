package config

import (
	"encoding/json"
	"errors"
	"os"
	"sync"
)

// Session is the persisted login state. Only the refresh token is worth keeping: the access token
// lasts minutes and is re-issued on every refresh.
type Session struct {
	// ServerURL records which backend issued the token, so switching servers forces a new login.
	ServerURL    string `json:"server_url"`
	RefreshToken string `json:"refresh_token"`
}

// SessionStore persists a Session to a private file. It is safe for concurrent use.
type SessionStore struct {
	path string
	mu   sync.Mutex
}

// NewSessionStore returns a store backed by the file at path.
func NewSessionStore(path string) *SessionStore {
	return &SessionStore{path: path}
}

// Load returns the stored session, or a zero Session if none has been saved.
func (s *SessionStore) Load() (Session, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	data, err := os.ReadFile(s.path)
	if errors.Is(err, os.ErrNotExist) {
		return Session{}, nil
	}
	if err != nil {
		return Session{}, err
	}
	var sess Session
	if err := json.Unmarshal(data, &sess); err != nil {
		// A corrupt session is no worse than none: the user just logs in again.
		return Session{}, nil
	}
	return sess, nil
}

// Save persists sess, replacing any previous session.
func (s *SessionStore) Save(sess Session) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	data, err := json.Marshal(sess)
	if err != nil {
		return err
	}
	return writePrivate(s.path, data)
}

// Clear removes the stored session.
func (s *SessionStore) Clear() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if err := os.Remove(s.path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}
