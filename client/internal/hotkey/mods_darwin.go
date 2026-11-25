//go:build cgo

package hotkey

import "golang.design/x/hotkey"

const (
	modAlt   = hotkey.ModOption
	modSuper = hotkey.ModCmd
)
