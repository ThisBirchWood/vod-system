package notify

import (
	"reflect"
	"testing"
)

func TestDesktopCommand(t *testing.T) {
	got := desktopCommand("linux", "VoD Client", "Clip saved", "Clip 12:00")
	want := []string{"notify-send", "--app-name=VoD Client", "--expire-time=3000", "Clip saved", "Clip 12:00"}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("linux: got %q", got)
	}

	got = desktopCommand("darwin", "VoD Client", `Say "hi"`, `back\slash`)
	want = []string{"osascript", "-e", `display notification "back\\slash" with title "Say \"hi\""`}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("darwin: got %q", got)
	}

	if got := desktopCommand("windows", "a", "b", "c"); got != nil {
		t.Errorf("windows: got %q, want nil (log only)", got)
	}
}

func TestDesktopAlwaysLogs(t *testing.T) {
	var logged string
	Desktop{Log: func(s string) { logged = s }}.Notify("Marker added", "boss")
	if logged != "Marker added: boss" {
		t.Errorf("logged %q", logged)
	}
}

func TestFunc(t *testing.T) {
	var got [2]string
	var n Notifier = Func(func(title, body string) { got = [2]string{title, body} })
	n.Notify("a", "b")
	if got != [2]string{"a", "b"} {
		t.Errorf("got %v", got)
	}
}
