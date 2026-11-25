package notify

import "testing"

func TestFunc(t *testing.T) {
	var got [2]string
	var n Notifier = Func(func(title, body string) { got = [2]string{title, body} })
	n.Notify("a", "b")
	if got != [2]string{"a", "b"} {
		t.Errorf("got %v", got)
	}
}
