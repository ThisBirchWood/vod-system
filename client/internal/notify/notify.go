// Package notify shows desktop notifications, the client's stand-in for ShadowPlay's overlay toasts.
package notify

import (
	"fmt"
	"os/exec"
	"runtime"
	"strings"
)

// Notifier shows a short message to the user.
type Notifier interface {
	Notify(title, body string)
}

// Func adapts a function to Notifier.
type Func func(title, body string)

func (f Func) Notify(title, body string) { f(title, body) }

// Desktop shows native notifications where a notifier is available and always logs the message.
type Desktop struct {
	AppName string
	Log     func(string)
}

func (d Desktop) Notify(title, body string) {
	if d.Log != nil {
		d.Log(title + ": " + body)
	}
	cmd := desktopCommand(runtime.GOOS, d.AppName, title, body)
	if cmd == nil {
		return
	}
	if path, err := exec.LookPath(cmd[0]); err == nil {
		c := exec.Command(path, cmd[1:]...)
		if c.Start() == nil {
			go c.Wait()
		}
	}
}

// desktopCommand returns the command that shows a notification on goos, or nil if there is none.
func desktopCommand(goos, app, title, body string) []string {
	switch goos {
	case "linux", "freebsd", "openbsd":
		return []string{"notify-send", "--app-name=" + app, "--expire-time=3000", title, body}
	case "darwin":
		script := fmt.Sprintf("display notification %s with title %s", appleQuote(body), appleQuote(title))
		return []string{"osascript", "-e", script}
	default:
		return nil
	}
}

// appleQuote quotes s as an AppleScript string literal.
func appleQuote(s string) string {
	s = strings.ReplaceAll(s, `\`, `\\`)
	s = strings.ReplaceAll(s, `"`, `\"`)
	return `"` + s + `"`
}
