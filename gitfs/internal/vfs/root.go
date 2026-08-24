// Package vfs implements the FUSE surface of gitfs. It merges two
// sources into one tree: internal/store (read-only, lazy, backed by git
// objects) and internal/overlay (read-write, local, tracks only what
// differs from the base commit). See docs/architecture.md for the full
// design rationale.
package vfs

import (
	"strings"
	"sync"

	"github.com/hanwen/go-fuse/v2/fs"

	"github.com/anuragag/tools/gitfs/internal/overlay"
	"github.com/anuragag/tools/gitfs/internal/store"
)

// Root holds the state shared by every node in one gitfs mount: the git
// backing store, the local overlay, and which commit is currently
// checked out.
type Root struct {
	Store   *store.Store
	Overlay *overlay.Overlay
	UID     uint32
	GID     uint32

	mu     sync.RWMutex
	commit store.OID
	tree   store.OID

	// rootInode is set once the node returned by NewRoot has actually
	// been wired into a live *fs.Inode tree (i.e. after fs.Mount), so
	// Invalidate can walk cached children by path. It stays nil for a
	// Root used purely for testing store/overlay logic without a real
	// mount, in which case Invalidate is a safe no-op.
	rootInode *fs.Inode
}

// NewRoot resolves rev against st and returns a Root checked out at that
// commit, plus the InodeEmbedder to pass to fs.Mount as the mount's root
// directory.
func NewRoot(st *store.Store, ovl *overlay.Overlay, rev string, uid, gid uint32) (*Root, fs.InodeEmbedder, error) {
	r := &Root{Store: st, Overlay: ovl, UID: uid, GID: gid}
	c, err := st.ResolveCommit(rev)
	if err != nil {
		return nil, nil, err
	}
	r.commit = c.OID
	r.tree = c.Tree

	rootNode := &Node{root: r, path: "", isDir: true}
	// EmbeddedInode() returns the address of rootNode's embedded
	// fs.Inode; it becomes "live" (usable for GetChild/Notify* calls)
	// once fs.Mount wires it up, but the pointer itself is valid now.
	r.rootInode = rootNode.EmbeddedInode()
	return r, rootNode, nil
}

// Base returns the commit and root tree currently checked out.
func (r *Root) Base() (commit, tree store.OID) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.commit, r.tree
}

// SetBase updates the checked-out commit. Callers (see internal/checkout)
// are responsible for reconciling the overlay and invalidating any FUSE
// inodes whose base content changed.
func (r *Root) SetBase(commit, tree store.OID) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.commit = commit
	r.tree = tree
}

// Invalidate tells the kernel to drop any cached dentry and page-cache
// content it holds for path, following a checkout that changed what
// path resolves to. It only has to walk inodes the kernel already
// bothered to look up (found via GetChild, which is nil for anything
// never touched) -- an untouched path needs no invalidation because
// nothing cached it in the first place, and a freshly-looked-up path
// picks up the new base content automatically since Node methods always
// resolve against the *current* base tree rather than caching it.
func (r *Root) Invalidate(path string) {
	if r.rootInode == nil {
		return
	}
	dir, name := splitParentName(path)
	parent := r.findCachedInode(dir)
	if parent == nil {
		return
	}
	_ = parent.NotifyEntry(name)
	if child := parent.GetChild(name); child != nil {
		// A zero-length range is a no-op to the kernel; pass a size
		// large enough to cover any real file so the whole cached
		// page range is dropped regardless of the old or new size.
		_ = child.NotifyContent(0, 1<<62)
	}
}

func (r *Root) findCachedInode(dir string) *fs.Inode {
	cur := r.rootInode
	if dir == "" {
		return cur
	}
	for _, part := range strings.Split(dir, "/") {
		cur = cur.GetChild(part)
		if cur == nil {
			return nil
		}
	}
	return cur
}

func splitParentName(path string) (dir, name string) {
	i := strings.LastIndexByte(path, '/')
	if i < 0 {
		return "", path
	}
	return path[:i], path[i+1:]
}
