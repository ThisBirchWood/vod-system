// Package auth obtains a Google ID token for the backend via the browser.
//
// The backend verifies ID tokens against its web OAuth client ID, so the client uses that same
// client with the implicit id_token flow: the browser is sent to Google, which redirects to a
// loopback page served here. The token arrives in the URL fragment, which never reaches a server,
// so the page posts it back to the loopback server with a little JavaScript. No client secret is
// needed, but http://localhost:<port>/callback must be an authorised redirect URI on the client.
package auth

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"time"
)

// DefaultAuthEndpoint is Google's OAuth 2.0 authorisation endpoint.
const DefaultAuthEndpoint = "https://accounts.google.com/o/oauth2/v2/auth"

// GoogleLogin runs the browser login flow.
type GoogleLogin struct {
	ClientID string
	Port     int
	// AuthEndpoint defaults to DefaultAuthEndpoint; tests point it elsewhere.
	AuthEndpoint string
	// OpenBrowser opens the URL; it defaults to the platform's opener. Errors are not fatal: the
	// URL is also passed to Prompt so the user can open it by hand.
	OpenBrowser func(string) error
	// Prompt is told the URL being opened, so it can be shown to the user.
	Prompt func(string)
}

// IDToken runs the flow and returns the Google ID token, waiting until the user finishes, ctx is
// cancelled or five minutes pass.
func (g GoogleLogin) IDToken(ctx context.Context) (string, error) {
	if g.ClientID == "" {
		return "", errors.New("google_client_id is not configured (it is the same GOOGLE_CLIENT_ID the backend uses)")
	}

	ln, err := net.Listen("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(g.Port)))
	if err != nil {
		return "", fmt.Errorf("listen for login callback on port %d: %w", g.Port, err)
	}

	state, nonce := randomToken(), randomToken()
	redirect := "http://localhost:" + strconv.Itoa(ln.Addr().(*net.TCPAddr).Port) + "/callback"

	result := make(chan loginResult, 1)
	srv := &http.Server{
		Handler:           g.handler(state, nonce, result),
		ReadHeaderTimeout: 10 * time.Second,
	}
	go srv.Serve(ln)
	defer srv.Close()

	authURL := g.authURL(redirect, state, nonce)
	if g.Prompt != nil {
		g.Prompt(authURL)
	}
	open := g.OpenBrowser
	if open == nil {
		open = OpenBrowser
	}
	_ = open(authURL)

	ctx, cancel := context.WithTimeout(ctx, 5*time.Minute)
	defer cancel()
	select {
	case r := <-result:
		return r.token, r.err
	case <-ctx.Done():
		return "", fmt.Errorf("login not completed: %w", ctx.Err())
	}
}

type loginResult struct {
	token string
	err   error
}

func (g GoogleLogin) authURL(redirect, state, nonce string) string {
	endpoint := g.AuthEndpoint
	if endpoint == "" {
		endpoint = DefaultAuthEndpoint
	}
	q := url.Values{
		"client_id":     {g.ClientID},
		"redirect_uri":  {redirect},
		"response_type": {"id_token"},
		"scope":         {"openid email profile"},
		"state":         {state},
		"nonce":         {nonce},
		"prompt":        {"select_account"},
	}
	return endpoint + "?" + q.Encode()
}

func (g GoogleLogin) handler(state, nonce string, result chan<- loginResult) http.Handler {
	deliver := func(r loginResult) {
		select {
		case result <- r:
		default: // a result was already delivered; ignore repeats
		}
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /callback", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Write([]byte(callbackPage))
	})
	mux.HandleFunc("POST /token", func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseForm(); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		// The state check is what stops another local page from injecting a token.
		if subtle.ConstantTimeCompare([]byte(r.PostForm.Get("state")), []byte(state)) != 1 {
			http.Error(w, "state mismatch", http.StatusBadRequest)
			return
		}
		if e := r.PostForm.Get("error"); e != "" {
			http.Error(w, "login failed", http.StatusBadRequest)
			deliver(loginResult{err: fmt.Errorf("google login failed: %s", e)})
			return
		}
		token := r.PostForm.Get("id_token")
		if err := checkNonce(token, nonce); err != nil {
			http.Error(w, "invalid token", http.StatusBadRequest)
			deliver(loginResult{err: err})
			return
		}
		w.WriteHeader(http.StatusNoContent)
		deliver(loginResult{token: token})
	})
	return mux
}

// checkNonce confirms the token was minted for this login attempt. Its signature is verified by
// the backend, so it is only decoded here.
func checkNonce(token, nonce string) error {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return errors.New("malformed ID token")
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return errors.New("malformed ID token payload")
	}
	var claims struct {
		Nonce string `json:"nonce"`
	}
	if err := json.Unmarshal(payload, &claims); err != nil {
		return errors.New("malformed ID token claims")
	}
	if subtle.ConstantTimeCompare([]byte(claims.Nonce), []byte(nonce)) != 1 {
		return errors.New("ID token nonce mismatch")
	}
	return nil
}

func randomToken() string {
	b := make([]byte, 24)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return base64.RawURLEncoding.EncodeToString(b)
}

// OpenBrowser opens url in the default browser.
func OpenBrowser(url string) error {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	case "darwin":
		cmd = exec.Command("open", url)
	default:
		cmd = exec.Command("xdg-open", url)
	}
	if err := cmd.Start(); err != nil {
		return err
	}
	go cmd.Wait()
	return nil
}

// callbackPage forwards the fragment Google redirected with to the loopback server.
const callbackPage = `<!doctype html>
<html><head><meta charset="utf-8"><title>VoD Client login</title>
<style>body{font-family:system-ui,sans-serif;display:grid;place-items:center;height:100vh;margin:0}</style>
</head><body><p id="msg">Finishing login…</p>
<script>
const params = new URLSearchParams(location.hash.slice(1));
history.replaceState(null, "", location.pathname);
fetch("/token", {method: "POST", body: params})
  .then(r => { document.getElementById("msg").textContent = r.ok
      ? "Logged in. You can close this tab and return to VoD Client."
      : "Login failed. Check the VoD Client output."; })
  .catch(() => { document.getElementById("msg").textContent = "Login failed: VoD Client is not listening."; });
</script></body></html>`
