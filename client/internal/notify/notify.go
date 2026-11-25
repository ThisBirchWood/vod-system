// Package notify defines how the client tells the user something happened.
package notify

// Notifier shows a short message to the user.
type Notifier interface {
	Notify(title, body string)
}

// Func adapts a function to Notifier.
type Func func(title, body string)

func (f Func) Notify(title, body string) { f(title, body) }
