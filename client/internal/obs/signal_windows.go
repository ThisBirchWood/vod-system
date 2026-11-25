//go:build windows

package obs

import "os"

// Unused on Windows (Stop kills the process instead); defined so the code compiles everywhere.
var sigterm = os.Kill
