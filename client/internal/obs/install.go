package obs

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

// FlatpakID is OBS Studio's Flathub application ID.
const FlatpakID = "com.obsproject.Studio"

// ErrNotInstalled means no OBS installation was found.
var ErrNotInstalled = errors.New("OBS Studio is not installed")

// System is the slice of the operating system this package touches, so it can be faked in tests.
type System struct {
	GOOS     string
	Home     string
	Getenv   func(string) string
	LookPath func(string) (string, error)
	Exists   func(string) bool
	// Run runs a command to completion with output going to the user.
	Run func(ctx context.Context, name string, args ...string) error
	// Output runs a command and returns its stdout.
	Output func(ctx context.Context, name string, args ...string) ([]byte, error)
	// Interactive reports whether a terminal is attached, so sudo can prompt for a password.
	Interactive bool
	IsRoot      bool
}

// HostSystem returns a System backed by the real OS, writing command output to out.
func HostSystem(out io.Writer) System {
	home, _ := os.UserHomeDir()
	stat, _ := os.Stdin.Stat()
	return System{
		GOOS:     runtime.GOOS,
		Home:     home,
		Getenv:   os.Getenv,
		LookPath: exec.LookPath,
		Exists: func(p string) bool {
			_, err := os.Stat(p)
			return err == nil
		},
		Run: func(ctx context.Context, name string, args ...string) error {
			cmd := exec.CommandContext(ctx, name, args...)
			cmd.Stdin, cmd.Stdout, cmd.Stderr = os.Stdin, out, out
			return cmd.Run()
		},
		Output: func(ctx context.Context, name string, args ...string) ([]byte, error) {
			return exec.CommandContext(ctx, name, args...).Output()
		},
		Interactive: stat != nil && stat.Mode()&os.ModeCharDevice != 0,
		IsRoot:      os.Geteuid() == 0,
	}
}

// Installation is a located OBS install.
type Installation struct {
	// Command and Args launch OBS; Args come before any OBS flags.
	Command string
	Args    []string
	// Dir is the working directory OBS must start in (it loads locale files relative to it on Windows).
	Dir string
	// ConfigDir is OBS's per-user configuration directory.
	ConfigDir string
	// Flatpak reports whether this is the Flathub build.
	Flatpak bool
}

// Find locates OBS. override, if set, is an explicit path to the OBS executable.
func Find(ctx context.Context, sys System, override string) (Installation, error) {
	cfgDir := nativeConfigDir(sys)
	if override != "" {
		if !sys.Exists(override) {
			return Installation{}, fmt.Errorf("obs.path %q does not exist", override)
		}
		return Installation{Command: override, Dir: filepath.Dir(override), ConfigDir: cfgDir}, nil
	}

	switch sys.GOOS {
	case "windows":
		for _, env := range []string{"ProgramFiles", "ProgramW6432", "ProgramFiles(x86)"} {
			base := sys.Getenv(env)
			if base == "" {
				continue
			}
			dir := filepath.Join(base, "obs-studio", "bin", "64bit")
			if exe := filepath.Join(dir, "obs64.exe"); sys.Exists(exe) {
				return Installation{Command: exe, Dir: dir, ConfigDir: cfgDir}, nil
			}
		}
	case "darwin":
		for _, app := range []string{"/Applications/OBS.app", filepath.Join(sys.Home, "Applications", "OBS.app")} {
			if exe := filepath.Join(app, "Contents", "MacOS", "OBS"); sys.Exists(exe) {
				return Installation{Command: exe, ConfigDir: cfgDir}, nil
			}
		}
	default:
		if p, err := sys.LookPath("obs"); err == nil {
			return Installation{Command: p, ConfigDir: cfgDir}, nil
		}
		if flatpak, err := sys.LookPath("flatpak"); err == nil {
			if _, err := sys.Output(ctx, flatpak, "info", FlatpakID); err == nil {
				return Installation{
					Command:   flatpak,
					Args:      []string{"run", FlatpakID},
					ConfigDir: filepath.Join(sys.Home, ".var", "app", FlatpakID, "config", "obs-studio"),
					Flatpak:   true,
				}, nil
			}
		}
	}
	return Installation{}, ErrNotInstalled
}

func nativeConfigDir(sys System) string {
	switch sys.GOOS {
	case "windows":
		return filepath.Join(sys.Getenv("APPDATA"), "obs-studio")
	case "darwin":
		return filepath.Join(sys.Home, "Library", "Application Support", "obs-studio")
	default:
		if xdg := sys.Getenv("XDG_CONFIG_HOME"); xdg != "" {
			return filepath.Join(xdg, "obs-studio")
		}
		return filepath.Join(sys.Home, ".config", "obs-studio")
	}
}

// Command is one step of an install plan.
type Command struct {
	Name string
	Args []string
}

func (c Command) String() string { return strings.Join(append([]string{c.Name}, c.Args...), " ") }

// InstallPlan returns the commands that install OBS on this system. On Linux it prefers a user
// level Flatpak install, which needs no root, over the distribution's package manager.
func InstallPlan(sys System) ([]Command, error) {
	switch sys.GOOS {
	case "windows":
		if _, err := sys.LookPath("winget"); err != nil {
			return nil, errors.New("winget not found; install OBS from https://obsproject.com")
		}
		return []Command{{"winget", []string{"install", "--exact", "--id", "OBSProject.OBSStudio",
			"--silent", "--accept-package-agreements", "--accept-source-agreements"}}}, nil
	case "darwin":
		if _, err := sys.LookPath("brew"); err != nil {
			return nil, errors.New("Homebrew not found; install OBS from https://obsproject.com")
		}
		return []Command{{"brew", []string{"install", "--cask", "obs"}}}, nil
	}

	if _, err := sys.LookPath("flatpak"); err == nil {
		return []Command{
			{"flatpak", []string{"remote-add", "--user", "--if-not-exists", "flathub", "https://dl.flathub.org/repo/flathub.flatpakrepo"}},
			{"flatpak", []string{"install", "--user", "--noninteractive", "-y", "flathub", FlatpakID}},
		}, nil
	}

	managers := []struct {
		bin  string
		args []string
	}{
		{"apt-get", []string{"install", "-y", "obs-studio"}},
		{"dnf", []string{"install", "-y", "obs-studio"}},
		{"pacman", []string{"-S", "--noconfirm", "--needed", "obs-studio"}},
		{"zypper", []string{"--non-interactive", "install", "obs-studio"}},
	}
	for _, m := range managers {
		if _, err := sys.LookPath(m.bin); err != nil {
			continue
		}
		cmd := Command{m.bin, m.args}
		if sys.IsRoot {
			return []Command{cmd}, nil
		}
		elevate, err := elevator(sys)
		if err != nil {
			return nil, err
		}
		return []Command{{elevate, append([]string{m.bin}, m.args...)}}, nil
	}
	return nil, errors.New("no supported package manager found (flatpak, apt-get, dnf, pacman, zypper); install OBS manually")
}

// elevator picks how to gain root: sudo when it can prompt in the terminal, else a polkit dialog.
func elevator(sys System) (string, error) {
	if _, err := sys.LookPath("sudo"); err == nil && sys.Interactive {
		return "sudo", nil
	}
	if _, err := sys.LookPath("pkexec"); err == nil {
		return "pkexec", nil
	}
	if _, err := sys.LookPath("sudo"); err == nil {
		return "sudo", nil
	}
	return "", errors.New("installing OBS needs root, but neither sudo nor pkexec is available")
}

// Install runs the install plan, reporting each step through log.
func Install(ctx context.Context, sys System, log func(string)) error {
	plan, err := InstallPlan(sys)
	if err != nil {
		return err
	}
	for _, c := range plan {
		log("running: " + c.String())
		if err := sys.Run(ctx, c.Name, c.Args...); err != nil {
			return fmt.Errorf("%s: %w", c, err)
		}
	}
	return nil
}

// IsRunning reports whether an OBS process is already running for this user.
func IsRunning(ctx context.Context, sys System) bool {
	switch sys.GOOS {
	case "windows":
		out, err := sys.Output(ctx, "tasklist", "/FI", "IMAGENAME eq obs64.exe", "/NH")
		return err == nil && strings.Contains(strings.ToLower(string(out)), "obs64.exe")
	case "darwin":
		_, err := sys.Output(ctx, "pgrep", "-x", "OBS")
		return err == nil
	default:
		_, err := sys.Output(ctx, "pgrep", "-x", "obs")
		return err == nil
	}
}
