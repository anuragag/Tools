package vfs

import (
	"time"

	gofuse "github.com/hanwen/go-fuse/v2/fs"
	"github.com/hanwen/go-fuse/v2/fuse"

	"github.com/anuragag/tools/gitfs/internal/overlay"
	"github.com/anuragag/tools/gitfs/internal/store"
)

// MountOptions configures a gitfs mount.
type MountOptions struct {
	// Rev is the git revision to check out initially (branch, tag, or
	// commit). Defaults to HEAD.
	Rev string
	// FSName / VolumeName are cosmetic, shown by `mount`/`df`.
	FSName string
	// AllowOther permits users other than the mount owner to access the
	// filesystem (requires `user_allow_other` in /etc/fuse.conf, or
	// running as root -- the expected mode inside a gVisor sandbox).
	AllowOther bool
	// Debug logs every FUSE operation; useful when developing, noisy in
	// production.
	Debug bool
}

// Mount starts serving gitDir/overlayDir at mountpoint and returns once
// the filesystem is ready to accept requests. The returned *Root lets
// callers drive checkout (internal/checkout) and status (internal/status)
// against the live mount; call server.Unmount() (on the returned
// *fuse.Server) to tear it down.
func Mount(mountpoint, gitDir, overlayDir string, opts MountOptions) (*fuse.Server, *Root, *store.Store, error) {
	if opts.Rev == "" {
		opts.Rev = "HEAD"
	}
	if opts.FSName == "" {
		opts.FSName = "gitfs"
	}

	st, err := store.Open(gitDir, store.Options{})
	if err != nil {
		return nil, nil, nil, err
	}

	ovl, err := overlay.Open(overlayDir)
	if err != nil {
		st.Close()
		return nil, nil, nil, err
	}

	root, rootNode, err := NewRoot(st, ovl, opts.Rev, 0, 0)
	if err != nil {
		st.Close()
		return nil, nil, nil, err
	}

	timeout := time.Second
	server, err := gofuse.Mount(mountpoint, rootNode, &gofuse.Options{
		MountOptions: fuse.MountOptions{
			FsName: opts.FSName,
			Name:   "gitfs",
			// gVisor sandboxes and most agent containers run as root
			// with no `fusermount` SUID helper installed; mount via
			// the syscall directly instead of shelling out to it.
			DirectMount: true,
			AllowOther:  opts.AllowOther,
			Debug:       opts.Debug,
		},
		EntryTimeout: &timeout,
		AttrTimeout:  &timeout,
	})
	if err != nil {
		st.Close()
		return nil, nil, nil, err
	}
	return server, root, st, nil
}
