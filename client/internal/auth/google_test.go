package auth

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"
)

// fakeIDToken builds an unsigned JWT with the given claims.
func fakeIDToken(claims map[string]any) string {
	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"RS256"}`))
	payload, _ := json.Marshal(claims)
	return header + "." + base64.RawURLEncoding.EncodeToString(payload) + ".sig"
}

func freePort(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	return ln.Addr().(*net.TCPAddr).Port
}

// googleThatRedirects plays Google and the browser: it reads the auth URL and posts back to the
// loopback server the way the callback page's JavaScript would.
func googleThatRedirects(t *testing.T, mutate func(form url.Values, q url.Values)) func(string) error {
	return func(authURL string) error {
		u, err := url.Parse(authURL)
		if err != nil {
			t.Error(err)
			return err
		}
		q := u.Query()
		redirect := q.Get("redirect_uri")
		// The real flow lands on /callback first; check it serves the forwarding page.
		go func() {
			resp, err := http.Get(strings.Replace(redirect, "localhost", "127.0.0.1", 1))
			if err != nil {
				t.Error(err)
				return
			}
			resp.Body.Close()

			form := url.Values{
				"state":    {q.Get("state")},
				"id_token": {fakeIDToken(map[string]any{"nonce": q.Get("nonce"), "sub": "123"})},
			}
			if mutate != nil {
				mutate(form, q)
			}
			tokenURL := strings.Replace(strings.TrimSuffix(redirect, "/callback")+"/token", "localhost", "127.0.0.1", 1)
			resp, err = http.PostForm(tokenURL, form)
			if err == nil {
				resp.Body.Close()
			}
		}()
		return nil
	}
}

func TestIDTokenHappyPath(t *testing.T) {
	var prompted string
	g := GoogleLogin{
		ClientID:    "client-123",
		Port:        freePort(t),
		OpenBrowser: googleThatRedirects(t, nil),
		Prompt:      func(u string) { prompted = u },
	}
	token, err := g.IDToken(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if err := checkNonce(token, "wrong"); err == nil {
		t.Error("token should carry the login's nonce, not an arbitrary one")
	}

	u, _ := url.Parse(prompted)
	q := u.Query()
	if u.Host != "accounts.google.com" {
		t.Errorf("auth host = %s", u.Host)
	}
	if q.Get("client_id") != "client-123" || q.Get("response_type") != "id_token" {
		t.Errorf("auth query = %v", q)
	}
	if q.Get("redirect_uri") != "http://localhost:"+strconv.Itoa(g.Port)+"/callback" {
		t.Errorf("redirect_uri = %s", q.Get("redirect_uri"))
	}
	if !strings.Contains(q.Get("scope"), "openid") || !strings.Contains(q.Get("scope"), "email") {
		t.Errorf("scope = %s", q.Get("scope"))
	}
	if q.Get("state") == "" || q.Get("nonce") == "" || q.Get("state") == q.Get("nonce") {
		t.Error("state and nonce must be present and distinct")
	}
}

func TestIDTokenRejectsWrongState(t *testing.T) {
	g := GoogleLogin{
		ClientID: "c",
		Port:     freePort(t),
		OpenBrowser: googleThatRedirects(t, func(form, _ url.Values) {
			form.Set("state", "attacker")
		}),
	}
	ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
	defer cancel()
	// A forged post is ignored rather than failing the login, so the real one can still arrive.
	if _, err := g.IDToken(ctx); err == nil {
		t.Fatal("login with a forged state must not succeed")
	}
}

func TestIDTokenRejectsWrongNonce(t *testing.T) {
	g := GoogleLogin{
		ClientID: "c",
		Port:     freePort(t),
		OpenBrowser: googleThatRedirects(t, func(form, _ url.Values) {
			form.Set("id_token", fakeIDToken(map[string]any{"nonce": "replayed"}))
		}),
	}
	_, err := g.IDToken(context.Background())
	if err == nil || !strings.Contains(err.Error(), "nonce") {
		t.Fatalf("got %v, want nonce mismatch", err)
	}
}

func TestIDTokenReportsGoogleError(t *testing.T) {
	g := GoogleLogin{
		ClientID: "c",
		Port:     freePort(t),
		OpenBrowser: googleThatRedirects(t, func(form, _ url.Values) {
			form.Del("id_token")
			form.Set("error", "access_denied")
		}),
	}
	_, err := g.IDToken(context.Background())
	if err == nil || !strings.Contains(err.Error(), "access_denied") {
		t.Fatalf("got %v, want access_denied", err)
	}
}

func TestIDTokenRequiresClientID(t *testing.T) {
	_, err := GoogleLogin{Port: freePort(t)}.IDToken(context.Background())
	if err == nil || !strings.Contains(err.Error(), "google_client_id") {
		t.Fatalf("got %v", err)
	}
}

func TestIDTokenPortInUse(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	g := GoogleLogin{ClientID: "c", Port: ln.Addr().(*net.TCPAddr).Port, OpenBrowser: func(string) error { return nil }}
	if _, err := g.IDToken(context.Background()); err == nil {
		t.Fatal("expected a listen error")
	}
}

func TestCheckNonce(t *testing.T) {
	good := fakeIDToken(map[string]any{"nonce": "n1"})
	if err := checkNonce(good, "n1"); err != nil {
		t.Errorf("valid nonce rejected: %v", err)
	}
	for _, bad := range []string{"", "a.b", "a.!!!.c", "a." + base64.RawURLEncoding.EncodeToString([]byte("nope")) + ".c", good} {
		if err := checkNonce(bad, "other"); err == nil {
			t.Errorf("checkNonce(%q) accepted", bad)
		}
	}
}
