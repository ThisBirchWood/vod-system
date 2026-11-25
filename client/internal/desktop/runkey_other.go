//go:build !windows

package desktop

import "errors"

func setRunKey(string, string, bool) error {
	return errors.New("the Run registry key only exists on Windows")
}

func runKeyExists(string) bool { return false }
