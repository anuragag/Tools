// Package statedir computes where gitfsd keeps a mount's local state
// (the overlay and its control socket), keyed by the mountpoint so that
// `gitfsd status <mountpoint>` and `gitfsd checkout <mountpoint> <rev>`
// can find their way back to the daemon that `gitfsd mount` started
// without any separate registry.
package statedir

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
)

// For returns the per-mount state directory for mountpoint,
// creating it if necessary. It defaults to
// $XDG_STATE_HOME/gitfs/mounts/<hash> (or ~/.local/state/... if
// XDG_STATE_HOME is unset), overridable wholesale via $GITFS_STATE_DIR.
func For(mountpoint string) (string, error) {
	abs, err := filepath.Abs(mountpoint)
	if err != nil {
		return "", fmt.Errorf("gitfs/statedir: resolving %s: %w", mountpoint, err)
	}
	sum := sha256.Sum256([]byte(abs))
	key := hex.EncodeToString(sum[:])[:16]

	base := os.Getenv("GITFS_STATE_DIR")
	if base == "" {
		xdg := os.Getenv("XDG_STATE_HOME")
		if xdg == "" {
			home, err := os.UserHomeDir()
			if err != nil {
				return "", fmt.Errorf("gitfs/statedir: resolving home directory: %w", err)
			}
			xdg = filepath.Join(home, ".local", "state")
		}
		base = filepath.Join(xdg, "gitfs")
	}

	dir := filepath.Join(base, "mounts", key)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", fmt.Errorf("gitfs/statedir: creating %s: %w", dir, err)
	}
	return dir, nil
}

// SocketPath returns the control-socket path within a mount's state dir.
func SocketPath(stateDir string) string {
	return filepath.Join(stateDir, "ctl.sock")
}

// OverlayDir returns the overlay directory within a mount's state dir.
func OverlayDir(stateDir string) string {
	return filepath.Join(stateDir, "overlay")
}
