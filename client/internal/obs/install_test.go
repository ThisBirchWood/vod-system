package obs

import (
	"context"
	"errors"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// fakeSystem builds a System where only the listed binaries and files exist.
func fakeSystem(goos string, bins []string, files []string) (System, *[]string) {
	var ran []string
	binSet := map[string]bool{}
	for _, b := range bins {
		binSet[b] = true
	}
	fileSet := map[string]bool{}
	for _, f := range files {
		fileSet[f] = true
	}
	return System{
		GOOS: goos,
		Home: "/home/u",
		Getenv: func(k string) string {
			return map[string]string{
				"ProgramFiles": `C:\Program Files`,
				"APPDATA":      `C:\Users\u\AppData\Roaming`,
			}[k]
		},
		LookPath: func(name string) (string, error) {
			if binSet[name] {
				return "/usr/bin/" + name, nil
			}
			return "", exec.ErrNotFound
		},
		Exists: func(p string) bool { return fileSet[p] },
		Run: func(_ context.Context, name string, args ...string) error {
			ran = append(ran, strings.Join(append([]string{name}, args...), " "))
			return nil
		},
		Output: func(_ context.Context, name string, args ...string) ([]byte, error) {
			cmd := strings.Join(append([]string{name}, args...), " ")
			ran = append(ran, cmd)
			if fileSet["output:"+cmd] {
				return []byte("ok"), nil
			}
			return nil, errors.New("exit status 1")
		},
	}, &ran
}

func TestFindLinuxNative(t *testing.T) {
	sys, _ := fakeSystem("linux", []string{"obs", "flatpak"}, nil)
	inst, err := Find(context.Background(), sys, "")
	if err != nil {
		t.Fatal(err)
	}
	if inst.Command != "/usr/bin/obs" || inst.Flatpak {
		t.Errorf("native obs should win over flatpak: %+v", inst)
	}
	if inst.ConfigDir != filepath.Join("/home/u", ".config", "obs-studio") {
		t.Errorf("config dir = %s", inst.ConfigDir)
	}
}

func TestFindLinuxXDGConfig(t *testing.T) {
	sys, _ := fakeSystem("linux", []string{"obs"}, nil)
	sys.Getenv = func(k string) string {
		if k == "XDG_CONFIG_HOME" {
			return "/xdg"
		}
		return ""
	}
	inst, _ := Find(context.Background(), sys, "")
	if inst.ConfigDir != filepath.Join("/xdg", "obs-studio") {
		t.Errorf("config dir = %s", inst.ConfigDir)
	}
}

func TestFindLinuxFlatpak(t *testing.T) {
	sys, _ := fakeSystem("linux", []string{"flatpak"}, []string{"output:/usr/bin/flatpak info " + FlatpakID})
	inst, err := Find(context.Background(), sys, "")
	if err != nil {
		t.Fatal(err)
	}
	if !inst.Flatpak || inst.Command != "/usr/bin/flatpak" || !reflect.DeepEqual(inst.Args, []string{"run", FlatpakID}) {
		t.Errorf("inst = %+v", inst)
	}
	want := filepath.Join("/home/u", ".var", "app", FlatpakID, "config", "obs-studio")
	if inst.ConfigDir != want {
		t.Errorf("config dir = %s, want %s", inst.ConfigDir, want)
	}
}

func TestFindLinuxFlatpakWithoutOBS(t *testing.T) {
	sys, _ := fakeSystem("linux", []string{"flatpak"}, nil)
	if _, err := Find(context.Background(), sys, ""); !errors.Is(err, ErrNotInstalled) {
		t.Fatalf("got %v, want ErrNotInstalled", err)
	}
}

func TestFindWindows(t *testing.T) {
	dir := filepath.Join(`C:\Program Files`, "obs-studio", "bin", "64bit")
	exe := filepath.Join(dir, "obs64.exe")
	sys, _ := fakeSystem("windows", nil, []string{exe})
	inst, err := Find(context.Background(), sys, "")
	if err != nil {
		t.Fatal(err)
	}
	if inst.Command != exe || inst.Dir != dir {
		t.Errorf("inst = %+v; OBS must start in its bin dir on Windows", inst)
	}
	if inst.ConfigDir != filepath.Join(`C:\Users\u\AppData\Roaming`, "obs-studio") {
		t.Errorf("config dir = %s", inst.ConfigDir)
	}
}

func TestFindDarwin(t *testing.T) {
	exe := filepath.Join("/Applications/OBS.app", "Contents", "MacOS", "OBS")
	sys, _ := fakeSystem("darwin", nil, []string{exe})
	inst, err := Find(context.Background(), sys, "")
	if err != nil {
		t.Fatal(err)
	}
	if inst.Command != exe {
		t.Errorf("inst = %+v", inst)
	}
	if inst.ConfigDir != filepath.Join("/home/u", "Library", "Application Support", "obs-studio") {
		t.Errorf("config dir = %s", inst.ConfigDir)
	}
}

func TestFindOverride(t *testing.T) {
	sys, _ := fakeSystem("linux", nil, []string{"/opt/obs/bin/obs"})
	inst, err := Find(context.Background(), sys, "/opt/obs/bin/obs")
	if err != nil || inst.Command != "/opt/obs/bin/obs" {
		t.Fatalf("got %+v, %v", inst, err)
	}
	if _, err := Find(context.Background(), sys, "/missing/obs"); err == nil {
		t.Error("a missing override should be an error, not silently ignored")
	}
}

func TestFindNotInstalled(t *testing.T) {
	for _, goos := range []string{"linux", "windows", "darwin"} {
		sys, _ := fakeSystem(goos, nil, nil)
		if _, err := Find(context.Background(), sys, ""); !errors.Is(err, ErrNotInstalled) {
			t.Errorf("%s: got %v, want ErrNotInstalled", goos, err)
		}
	}
}

func TestInstallPlan(t *testing.T) {
	tests := []struct {
		name        string
		goos        string
		bins        []string
		root        bool
		interactive bool
		want        []string
		wantErr     bool
	}{
		{
			name: "linux prefers user flatpak",
			goos: "linux", bins: []string{"flatpak", "apt-get", "sudo"},
			want: []string{
				"flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo",
				"flatpak install --user --noninteractive -y flathub " + FlatpakID,
			},
		},
		{
			name: "apt with sudo in a terminal",
			goos: "linux", bins: []string{"apt-get", "sudo", "pkexec"}, interactive: true,
			want: []string{"sudo apt-get install -y obs-studio"},
		},
		{
			name: "apt with polkit when detached",
			goos: "linux", bins: []string{"apt-get", "sudo", "pkexec"},
			want: []string{"pkexec apt-get install -y obs-studio"},
		},
		{
			name: "dnf as root needs no elevation",
			goos: "linux", bins: []string{"dnf"}, root: true,
			want: []string{"dnf install -y obs-studio"},
		},
		{
			name: "pacman",
			goos: "linux", bins: []string{"pacman", "sudo"}, interactive: true,
			want: []string{"sudo pacman -S --noconfirm --needed obs-studio"},
		},
		{
			name: "no way to become root",
			goos: "linux", bins: []string{"apt-get"}, wantErr: true,
		},
		{
			name: "no package manager",
			goos: "linux", wantErr: true,
		},
		{
			name: "windows winget",
			goos: "windows", bins: []string{"winget"},
			want: []string{"winget install --exact --id OBSProject.OBSStudio --silent --accept-package-agreements --accept-source-agreements"},
		},
		{
			name: "windows without winget", goos: "windows", wantErr: true,
		},
		{
			name: "macos homebrew",
			goos: "darwin", bins: []string{"brew"},
			want: []string{"brew install --cask obs"},
		},
		{
			name: "macos without homebrew", goos: "darwin", wantErr: true,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			sys, _ := fakeSystem(tt.goos, tt.bins, nil)
			sys.IsRoot, sys.Interactive = tt.root, tt.interactive
			plan, err := InstallPlan(sys)
			if tt.wantErr {
				if err == nil {
					t.Fatalf("expected an error, got plan %v", plan)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			var got []string
			for _, c := range plan {
				got = append(got, c.String())
			}
			if !reflect.DeepEqual(got, tt.want) {
				t.Errorf("plan:\n got %q\nwant %q", got, tt.want)
			}
		})
	}
}

func TestInstallRunsPlanAndStopsOnFailure(t *testing.T) {
	sys, ran := fakeSystem("linux", []string{"flatpak"}, nil)
	var logged []string
	if err := Install(context.Background(), sys, func(s string) { logged = append(logged, s) }); err != nil {
		t.Fatal(err)
	}
	if len(*ran) != 2 || len(logged) != 2 {
		t.Fatalf("ran %v, logged %v", *ran, logged)
	}

	sys, ran = fakeSystem("linux", []string{"flatpak"}, nil)
	sys.Run = func(_ context.Context, name string, args ...string) error {
		*ran = append(*ran, name)
		return errors.New("network down")
	}
	if err := Install(context.Background(), sys, func(string) {}); err == nil || !strings.Contains(err.Error(), "network down") {
		t.Fatalf("got %v", err)
	}
	if len(*ran) != 1 {
		t.Errorf("should stop after the first failing step, ran %v", *ran)
	}
}

func TestIsRunning(t *testing.T) {
	tests := []struct {
		goos, outputKey string
	}{
		{"linux", "pgrep -x obs"},
		{"darwin", "pgrep -x OBS"},
	}
	for _, tt := range tests {
		sys, _ := fakeSystem(tt.goos, nil, []string{"output:" + tt.outputKey})
		if !IsRunning(context.Background(), sys) {
			t.Errorf("%s: should detect running OBS", tt.goos)
		}
		sys, _ = fakeSystem(tt.goos, nil, nil)
		if IsRunning(context.Background(), sys) {
			t.Errorf("%s: no OBS running", tt.goos)
		}
	}

	sys, _ := fakeSystem("windows", nil, nil)
	sys.Output = func(context.Context, string, ...string) ([]byte, error) {
		return []byte("obs64.exe   1234 Console   1   200,000 K"), nil
	}
	if !IsRunning(context.Background(), sys) {
		t.Error("windows: should detect obs64.exe in tasklist output")
	}
	sys.Output = func(context.Context, string, ...string) ([]byte, error) {
		return []byte("INFO: No tasks are running which match the specified criteria."), nil
	}
	if IsRunning(context.Background(), sys) {
		t.Error("windows: tasklist reported nothing running")
	}
}
