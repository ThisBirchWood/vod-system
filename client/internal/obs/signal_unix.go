//go:build !windows

package obs

import "syscall"

var sigterm = syscall.SIGTERM
