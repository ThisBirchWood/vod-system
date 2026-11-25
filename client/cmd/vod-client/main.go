// Command vod-client streams to the VoD system through OBS and captures clips, markers and
// sections with global hotkeys, in the style of NVIDIA ShadowPlay.
//
//	vod-client [run]          log in, launch OBS, start streaming and listen for hotkeys
//	vod-client login          log in through the browser
//	vod-client logout         end the session
//	vod-client clip           save the last few seconds of the stream
//	vod-client mark [message] drop a marker on the stream
//	vod-client section        start, or finish and save, a section
//	vod-client status         show what the running instance is doing
//	vod-client config         show the config file location and contents
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"golang.design/x/hotkey/mainthread"

	"gitea.defaoite.dev/dylan/vod-system/client/internal/api"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/app"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/auth"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/config"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/control"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/notify"
	"gitea.defaoite.dev/dylan/vod-system/client/internal/obs"
)

func main() {
	// macOS delivers hotkey events on the main thread, so the program runs beside its event loop.
	code := 0
	mainthread.Init(func() { code = run(os.Args[1:], os.Stdout, os.Stderr) })
	os.Exit(code)
}

const usage = `usage: vod-client [command] [flags]

commands:
  run             log in, launch OBS, stream and listen for hotkeys (default)
  login           log in through the browser
  logout          end the session
  clip            save the last few seconds of the stream
  mark [message]  drop a marker on the stream
  section         start, or finish and save, a section
  status          show what the running instance is doing
  config          show the config file location and contents

run "vod-client <command> -h" for a command's flags
`

func run(args []string, stdout, stderr io.Writer) int {
	cmd := "run"
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		cmd, args = args[0], args[1:]
	}

	var err error
	switch cmd {
	case "run":
		err = cmdRun(args, stdout)
	case "login":
		err = cmdLogin(args, stdout)
	case "logout":
		err = cmdLogout(args, stdout)
	case app.ActionClip, app.ActionSection, app.ActionStatus:
		err = cmdControl(cmd, "", stdout)
	case app.ActionMarker:
		err = cmdControl(cmd, strings.Join(args, " "), stdout)
	case "config":
		err = cmdConfig(args, stdout)
	case "help", "-h", "--help":
		fmt.Fprint(stdout, usage)
	default:
		fmt.Fprintf(stderr, "unknown command %q\n\n%s", cmd, usage)
		return 2
	}

	if errors.Is(err, flag.ErrHelp) {
		return 0
	}
	if err != nil {
		fmt.Fprintln(stderr, "vod-client:", err)
		return 1
	}
	return 0
}

// common holds flags shared by commands that talk to the backend.
type common struct {
	configPath string
	server     string
	clientID   string
}

func (c *common) register(fs *flag.FlagSet) {
	defaultPath := ""
	if dir, err := config.Dir(); err == nil {
		defaultPath = filepath.Join(dir, "config.json")
	}
	fs.StringVar(&c.configPath, "config", defaultPath, "config file path")
	fs.StringVar(&c.server, "server", "", "backend URL, saved to the config (e.g. https://vod.example.com)")
	fs.StringVar(&c.clientID, "google-client-id", "", "Google OAuth client ID used by the backend, saved to the config")
}

// load reads the config, applying and persisting any flag overrides.
func (c *common) load(out io.Writer) (config.Config, error) {
	cfg, created, err := config.Load(c.configPath)
	if err != nil {
		return cfg, err
	}
	if created {
		fmt.Fprintf(out, "created config at %s\n", c.configPath)
	}
	changed := false
	if c.server != "" && c.server != cfg.ServerURL {
		cfg.ServerURL, changed = strings.TrimRight(c.server, "/"), true
	}
	if c.clientID != "" && c.clientID != cfg.GoogleClientID {
		cfg.GoogleClientID, changed = c.clientID, true
	}
	if err := cfg.Validate(); err != nil {
		return cfg, fmt.Errorf("%s: %w", c.configPath, err)
	}
	if changed {
		if err := config.Save(c.configPath, cfg); err != nil {
			return cfg, err
		}
	}
	return cfg, nil
}

func (c *common) client(cfg config.Config) (*api.Client, error) {
	store := config.NewSessionStore(filepath.Join(filepath.Dir(c.configPath), "session.json"))
	return api.New(cfg.ServerURL, store)
}

func googleLogin(cfg config.Config, out io.Writer) func(context.Context) (string, error) {
	return auth.GoogleLogin{
		ClientID: cfg.GoogleClientID,
		Port:     cfg.OAuthRedirectPort,
		Prompt: func(url string) {
			fmt.Fprintf(out, "opening your browser to log in; if it does not open, visit:\n  %s\n", url)
		},
	}.IDToken
}

func signalContext() (context.Context, context.CancelFunc) {
	return signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
}

func cmdRun(args []string, out io.Writer) error {
	fs := flag.NewFlagSet("run", flag.ContinueOnError)
	var c common
	c.register(fs)
	noInstall := fs.Bool("no-install", false, "do not install OBS if it is missing")
	if err := fs.Parse(args); err != nil {
		return err
	}

	cfg, err := c.load(out)
	if err != nil {
		return err
	}
	client, err := c.client(cfg)
	if err != nil {
		return err
	}
	socket, err := control.SocketPath()
	if err != nil {
		return err
	}

	logf := func(format string, a ...any) {
		fmt.Fprintf(out, "%s %s\n", time.Now().Format("15:04:05"), fmt.Sprintf(format, a...))
	}
	runner := &app.Runner{
		Config:     cfg,
		API:        client,
		Login:      googleLogin(cfg, out),
		System:     obs.HostSystem(out),
		Notifier:   notify.Desktop{AppName: "VoD Client"},
		SocketPath: socket,
		NoInstall:  *noInstall,
		Logf:       logf,
	}

	ctx, stop := signalContext()
	defer stop()
	err = runner.Run(ctx)
	logf("stopped")
	return err
}

func cmdLogin(args []string, out io.Writer) error {
	fs := flag.NewFlagSet("login", flag.ContinueOnError)
	var c common
	c.register(fs)
	if err := fs.Parse(args); err != nil {
		return err
	}
	cfg, err := c.load(out)
	if err != nil {
		return err
	}
	client, err := c.client(cfg)
	if err != nil {
		return err
	}

	ctx, stop := signalContext()
	defer stop()
	token, err := googleLogin(cfg, out)(ctx)
	if err != nil {
		return err
	}
	if err := client.Login(ctx, token); err != nil {
		return err
	}
	user, err := client.Me(ctx)
	if err != nil {
		return err
	}
	fmt.Fprintf(out, "logged in as %s\n", user.Username)
	return nil
}

func cmdLogout(args []string, out io.Writer) error {
	fs := flag.NewFlagSet("logout", flag.ContinueOnError)
	var c common
	c.register(fs)
	if err := fs.Parse(args); err != nil {
		return err
	}
	cfg, err := c.load(out)
	if err != nil {
		return err
	}
	client, err := c.client(cfg)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err := client.Logout(ctx); err != nil {
		fmt.Fprintf(out, "warning: could not revoke the session on the server: %v\n", err)
	}
	fmt.Fprintln(out, "logged out")
	return nil
}

func cmdControl(action, arg string, out io.Writer) error {
	socket, err := control.SocketPath()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	msg, err := control.Send(ctx, socket, control.Request{Action: action, Arg: arg})
	if err != nil {
		return err
	}
	fmt.Fprintln(out, msg)
	return nil
}

func cmdConfig(args []string, out io.Writer) error {
	fs := flag.NewFlagSet("config", flag.ContinueOnError)
	var c common
	c.register(fs)
	if err := fs.Parse(args); err != nil {
		return err
	}
	if _, err := c.load(out); err != nil {
		return err
	}
	data, err := os.ReadFile(c.configPath)
	if err != nil {
		return err
	}
	fmt.Fprintf(out, "%s\n\n%s", c.configPath, data)
	return nil
}
