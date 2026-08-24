package vfs

import (
	"context"
	"hash/fnv"
	"os"
	"path"
	"syscall"

	"github.com/hanwen/go-fuse/v2/fs"
	"github.com/hanwen/go-fuse/v2/fuse"

	"github.com/anuragag/tools/gitfs/internal/overlay"
	"github.com/anuragag/tools/gitfs/internal/store"
)

// Node is a lazily-populated FUSE tree node. It never eagerly reads more
// of the repository than the operation in hand requires: a Lookup for
// one file fetches (and caches) exactly one tree object, not the whole
// subtree, and a file's content is fetched from git only when something
// actually opens it for reading.
type Node struct {
	fs.Inode

	root  *Root
	path  string // repo-relative path, "" for the mount root; always slash-separated
	isDir bool
}

var (
	_ fs.InodeEmbedder  = (*Node)(nil)
	_ fs.NodeLookuper   = (*Node)(nil)
	_ fs.NodeReaddirer  = (*Node)(nil)
	_ fs.NodeGetattrer  = (*Node)(nil)
	_ fs.NodeSetattrer  = (*Node)(nil)
	_ fs.NodeOpener     = (*Node)(nil)
	_ fs.NodeCreater    = (*Node)(nil)
	_ fs.NodeMkdirer    = (*Node)(nil)
	_ fs.NodeUnlinker   = (*Node)(nil)
	_ fs.NodeRmdirer    = (*Node)(nil)
	_ fs.NodeRenamer    = (*Node)(nil)
	_ fs.NodeSymlinker  = (*Node)(nil)
	_ fs.NodeReadlinker = (*Node)(nil)
)

func join(dir, name string) string {
	if dir == "" {
		return name
	}
	return dir + "/" + name
}

// ino derives a stable inode number from a repo-relative path so the
// same path always maps to the same identity, without needing a
// persistent allocator.
func ino(p string, isDir bool) uint64 {
	h := fnv.New64a()
	_, _ = h.Write([]byte(p))
	v := h.Sum64()
	// Keep directories and files in visibly different ranges purely to
	// make debugging easier; collisions across the two are otherwise
	// harmless since StableAttr.Mode already disambiguates node type.
	if isDir {
		v |= 1 << 63
	} else {
		v &^= 1 << 63
	}
	if v == 0 {
		v = 1
	}
	return v
}

func (n *Node) stableAttr(isDir bool, p string) fs.StableAttr {
	mode := uint32(fuse.S_IFREG)
	if isDir {
		mode = fuse.S_IFDIR
	}
	return fs.StableAttr{Mode: mode, Ino: ino(p, isDir)}
}

// overlayEntry is a small convenience wrapper around the overlay lookup
// that also reports whether the path is a live (non-deleted) override.
func (n *Node) overlayEntry(p string) (entry overlay.Entry, live bool, tombstoned bool) {
	e, ok := n.root.Overlay.Get(p)
	if !ok {
		return overlay.Entry{}, false, false
	}
	if e.State == overlay.Deleted {
		return e, false, true
	}
	return e, true, false
}

// resolveBase looks up what n.path currently resolves to in the live
// base tree, walking fresh from Root.Base() every time rather than
// trusting any per-Node cached OID. This is what keeps gitfs correct
// across checkout: a checkout only has to update Root's own commit/tree
// pointer (internal/checkout does this after the invalidation pass), and
// every Node -- including ones the kernel had cached from before the
// checkout -- picks up the change on its next operation instead of
// needing to be individually retagged. The cost is an O(path depth)
// walk per call, cushioned by Store's tree cache; that is still
// proportional to how deep a path is, never to repository size.
func (n *Node) resolveBase() (store.TreeEntry, bool) {
	e, err := n.lookupBase(n.path)
	if err != nil {
		return store.TreeEntry{}, false
	}
	return e, true
}

// Lookup resolves one path component. It consults the overlay first
// (local edits always win), then falls back to the base git tree.
func (n *Node) Lookup(ctx context.Context, name string, out *fuse.EntryOut) (*fs.Inode, syscall.Errno) {
	childPath := join(n.path, name)

	if _, live, tombstoned := n.overlayEntry(childPath); tombstoned {
		return nil, syscall.ENOENT
	} else if live {
		fi, err := os.Lstat(n.root.Overlay.FilePath(childPath))
		if err != nil {
			return nil, fs.ToErrno(err)
		}
		child := &Node{root: n.root, path: childPath, isDir: fi.IsDir()}
		fillEntryFromLstat(out, fi, n.root)
		return n.NewInode(ctx, child, n.stableAttr(fi.IsDir(), childPath)), 0
	}

	base, ok := n.resolveBase()
	if !ok || base.Type != store.TypeTree {
		return nil, syscall.ENOENT
	}
	entries, err := n.root.Store.Tree(base.OID)
	if err != nil {
		return nil, syscall.EIO
	}
	for _, e := range entries {
		if e.Name != name {
			continue
		}
		isDir := e.Type == store.TypeTree || e.Type == store.TypeCommit
		child := &Node{root: n.root, path: childPath, isDir: isDir}
		if err := fillEntryFromGit(out, e, n.root); err != nil {
			return nil, syscall.EIO
		}
		return n.NewInode(ctx, child, n.stableAttr(isDir, childPath)), 0
	}
	return nil, syscall.ENOENT
}

// Readdir merges the overlay's dirty children of this directory with the
// base tree's entries, letting Added/Modified overlay entries shadow
// same-named base entries and Deleted entries mask them entirely.
func (n *Node) Readdir(ctx context.Context) (fs.DirStream, syscall.Errno) {
	seen := make(map[string]bool)
	deleted := make(map[string]bool)
	var items []fuse.DirEntry

	for _, e := range n.root.Overlay.Children(n.path) {
		name := path.Base(e.Path)
		if e.State == overlay.Deleted {
			deleted[name] = true
			continue
		}
		seen[name] = true
		mode := uint32(fuse.S_IFREG)
		if fi, err := os.Lstat(n.root.Overlay.FilePath(e.Path)); err == nil {
			switch {
			case fi.IsDir():
				mode = fuse.S_IFDIR
			case fi.Mode()&os.ModeSymlink != 0:
				mode = fuse.S_IFLNK
			}
		}
		items = append(items, fuse.DirEntry{Name: name, Mode: mode, Ino: ino(e.Path, mode == fuse.S_IFDIR)})
	}

	if base, ok := n.resolveBase(); ok && base.Type == store.TypeTree {
		entries, err := n.root.Store.Tree(base.OID)
		if err != nil {
			return nil, syscall.EIO
		}
		for _, e := range entries {
			if deleted[e.Name] || seen[e.Name] {
				continue
			}
			mode := uint32(fuse.S_IFREG)
			switch {
			case e.Type == store.TypeTree || e.Type == store.TypeCommit:
				mode = fuse.S_IFDIR
			case e.GitMode == gitModeSymlink:
				mode = fuse.S_IFLNK
			}
			items = append(items, fuse.DirEntry{Name: e.Name, Mode: mode, Ino: ino(join(n.path, e.Name), mode == fuse.S_IFDIR)})
		}
	}

	return fs.NewListDirStream(items), 0
}

const gitModeSymlink = "120000"
const gitModeExec = "100755"

// Getattr fills in size/mode/mtime, preferring the overlay's real file
// when the path is dirty and otherwise asking the store for a cheap
// (content-free) size probe.
func (n *Node) Getattr(ctx context.Context, f fs.FileHandle, out *fuse.AttrOut) syscall.Errno {
	if _, live, tombstoned := n.overlayEntry(n.path); tombstoned {
		return syscall.ENOENT
	} else if live {
		fi, err := os.Lstat(n.root.Overlay.FilePath(n.path))
		if err != nil {
			return fs.ToErrno(err)
		}
		out.Attr = *fuse.ToAttr(fi)
		out.Ino = ino(n.path, fi.IsDir())
		return 0
	}

	if n.isDir {
		out.Mode = fuse.S_IFDIR | 0o755
		out.Ino = ino(n.path, true)
		return 0
	}

	base, ok := n.resolveBase()
	if !ok {
		return syscall.ENOENT
	}

	if base.GitMode == gitModeSymlink {
		data, err := n.root.Store.Blob(base.OID)
		if err != nil {
			return fs.ToErrno(err)
		}
		out.Mode = fuse.S_IFLNK | 0o777
		out.Size = uint64(len(data))
		out.Ino = ino(n.path, false)
		return 0
	}

	st, err := n.root.Store.Stat(base.OID)
	if err != nil {
		return fs.ToErrno(err)
	}
	mode := uint32(0o644)
	if base.GitMode == gitModeExec {
		mode = 0o755
	}
	out.Mode = fuse.S_IFREG | mode
	out.Size = uint64(st.Size)
	out.Ino = ino(n.path, false)
	return 0
}

// Setattr handles truncate/chmod/utimes. Any attribute change forces
// materialization: once metadata diverges from the base commit the path
// is dirty regardless of whether content changed.
func (n *Node) Setattr(ctx context.Context, f fs.FileHandle, in *fuse.SetAttrIn, out *fuse.AttrOut) syscall.Errno {
	if errno := n.ensureMaterialized(); errno != 0 {
		return errno
	}
	p := n.root.Overlay.FilePath(n.path)

	if sz, ok := in.GetSize(); ok {
		if err := os.Truncate(p, int64(sz)); err != nil {
			return fs.ToErrno(err)
		}
	}
	if mode, ok := in.GetMode(); ok {
		if err := os.Chmod(p, os.FileMode(mode&0o777)); err != nil {
			return fs.ToErrno(err)
		}
	}
	if mtime, ok := in.GetMTime(); ok {
		atime := mtime
		if at, ok := in.GetATime(); ok {
			atime = at
		}
		if err := os.Chtimes(p, atime, mtime); err != nil {
			return fs.ToErrno(err)
		}
	}

	fi, err := os.Lstat(p)
	if err != nil {
		return fs.ToErrno(err)
	}
	out.Attr = *fuse.ToAttr(fi)
	out.Ino = ino(n.path, fi.IsDir())
	return 0
}

// ensureMaterialized promotes this path into the overlay if it is not
// already dirty there: base content (if any) is copied in and the path
// is marked Modified (or Added, if it had no base counterpart).
func (n *Node) ensureMaterialized() syscall.Errno {
	if _, live, tombstoned := n.overlayEntry(n.path); live || tombstoned {
		return 0
	}
	if err := n.root.Overlay.EnsureParent(n.path); err != nil {
		return fs.ToErrno(err)
	}
	base, hasBase := n.resolveBase()
	var data []byte
	if hasBase {
		d, err := n.root.Store.Blob(base.OID)
		if err != nil {
			return fs.ToErrno(err)
		}
		data = d
	}
	mode := os.FileMode(0o644)
	if hasBase && base.GitMode == gitModeExec {
		mode = 0o755
	}
	if err := os.WriteFile(n.root.Overlay.FilePath(n.path), data, mode); err != nil {
		return fs.ToErrno(err)
	}
	state := overlay.Modified
	oid := ""
	if hasBase {
		oid = string(base.OID)
	} else {
		state = overlay.Added
	}
	if err := n.root.Overlay.Set(n.path, state, false, oid); err != nil {
		return fs.ToErrno(err)
	}
	return 0
}

// blobHandle serves reads for a base-only (not yet materialized) file
// directly out of the store's cached blob content, with no local fd and
// no copy into the overlay. This is the read path that stays purely
// lazy: `cat`-ing a file that hasn't been touched never writes anything
// to local disk.
type blobHandle struct {
	data []byte
}

var _ fs.FileReader = (*blobHandle)(nil)

func (h *blobHandle) Read(ctx context.Context, dest []byte, off int64) (fuse.ReadResult, syscall.Errno) {
	if off < 0 || off >= int64(len(h.data)) {
		return fuse.ReadResultData(nil), 0
	}
	end := off + int64(len(dest))
	if end > int64(len(h.data)) {
		end = int64(len(h.data))
	}
	return fuse.ReadResultData(h.data[off:end]), 0
}

// Open decides, per the requested flags, whether a file can be served
// lazily from the store (read-only, no local copy) or must first be
// promoted into the overlay (any write intent).
func (n *Node) Open(ctx context.Context, flags uint32) (fs.FileHandle, uint32, syscall.Errno) {
	_, live, tombstoned := n.overlayEntry(n.path)
	if tombstoned {
		return nil, 0, syscall.ENOENT
	}
	wantWrite := flags&(syscall.O_WRONLY|syscall.O_RDWR) != 0

	if live {
		f, err := os.OpenFile(n.root.Overlay.FilePath(n.path), int(flags)&^syscall.O_CREAT, 0)
		if err != nil {
			return nil, 0, fs.ToErrno(err)
		}
		return fs.NewLoopbackFileFromOS(f), 0, 0
	}

	if !wantWrite {
		base, ok := n.resolveBase()
		if !ok {
			return nil, 0, syscall.ENOENT
		}
		data, err := n.root.Store.Blob(base.OID)
		if err != nil {
			return nil, 0, fs.ToErrno(err)
		}
		return &blobHandle{data: data}, fuse.FOPEN_KEEP_CACHE, 0
	}

	if errno := n.ensureMaterialized(); errno != 0 {
		return nil, 0, errno
	}
	f, err := os.OpenFile(n.root.Overlay.FilePath(n.path), int(flags)&^syscall.O_CREAT, 0)
	if err != nil {
		return nil, 0, fs.ToErrno(err)
	}
	return fs.NewLoopbackFileFromOS(f), 0, 0
}

// Create makes a brand-new file, which always lives purely in the
// overlay (there is by definition no base counterpart yet).
func (n *Node) Create(ctx context.Context, name string, flags uint32, mode uint32, out *fuse.EntryOut) (*fs.Inode, fs.FileHandle, uint32, syscall.Errno) {
	childPath := join(n.path, name)
	if err := n.root.Overlay.EnsureParent(childPath); err != nil {
		return nil, nil, 0, fs.ToErrno(err)
	}
	f, err := os.OpenFile(n.root.Overlay.FilePath(childPath), int(flags)|os.O_CREATE, os.FileMode(mode))
	if err != nil {
		return nil, nil, 0, fs.ToErrno(err)
	}
	if err := n.root.Overlay.Set(childPath, overlay.Added, false, ""); err != nil {
		f.Close()
		return nil, nil, 0, syscall.EIO
	}
	fi, err := f.Stat()
	if err != nil {
		f.Close()
		return nil, nil, 0, fs.ToErrno(err)
	}
	out.Attr = *fuse.ToAttr(fi)
	out.Ino = ino(childPath, false)

	child := &Node{root: n.root, path: childPath, isDir: false}
	inode := n.NewInode(ctx, child, n.stableAttr(false, childPath))
	return inode, fs.NewLoopbackFileFromOS(f), 0, 0
}

// Mkdir creates a new (initially empty) directory, tracked in the
// overlay so it survives even with no children yet.
func (n *Node) Mkdir(ctx context.Context, name string, mode uint32, out *fuse.EntryOut) (*fs.Inode, syscall.Errno) {
	childPath := join(n.path, name)
	if err := n.root.Overlay.EnsureParent(childPath); err != nil {
		return nil, fs.ToErrno(err)
	}
	dirPath := n.root.Overlay.FilePath(childPath)
	if err := os.Mkdir(dirPath, os.FileMode(mode)); err != nil {
		return nil, fs.ToErrno(err)
	}
	if err := n.root.Overlay.Set(childPath, overlay.Added, true, ""); err != nil {
		return nil, syscall.EIO
	}
	out.Mode = fuse.S_IFDIR | (mode & 0o777)
	out.Ino = ino(childPath, true)
	child := &Node{root: n.root, path: childPath, isDir: true}
	return n.NewInode(ctx, child, n.stableAttr(true, childPath)), 0
}

// Unlink removes a file. If the path had a base counterpart it becomes a
// Deleted tombstone (so the merged view hides the base entry); if it was
// purely an overlay addition, its overlay entry is dropped entirely.
func (n *Node) Unlink(ctx context.Context, name string) syscall.Errno {
	childPath := join(n.path, name)
	return n.remove(childPath)
}

// Rmdir removes an empty directory the same way Unlink removes a file.
// It relies on the kernel/VFS to have already refused non-empty
// directories via Readdir, matching how LoopbackNode delegates to the
// same OS syscall for both.
func (n *Node) Rmdir(ctx context.Context, name string) syscall.Errno {
	childPath := join(n.path, name)
	if n.root.Overlay.HasDirtyDescendant(childPath) {
		return syscall.ENOTEMPTY
	}
	if base, ok := n.resolveBase(); ok && base.Type == store.TypeTree {
		entries, err := n.root.Store.Tree(base.OID)
		if err == nil {
			for _, e := range entries {
				if e.Name == name {
					if children, err := n.root.Store.Tree(e.OID); err == nil && len(children) > 0 {
						return syscall.ENOTEMPTY
					}
				}
			}
		}
	}
	return n.remove(childPath)
}

func (n *Node) remove(childPath string) syscall.Errno {
	entry, ok := n.root.Overlay.Get(childPath)
	baseEntry, baseErr := n.lookupBase(childPath)
	hasBase := baseErr == nil && !baseEntry.OID.IsZero()

	if ok && entry.State == overlay.Deleted {
		return syscall.ENOENT
	}
	if !ok && !hasBase {
		return syscall.ENOENT
	}

	if err := n.root.Overlay.RemoveFile(childPath); err != nil {
		return fs.ToErrno(err)
	}
	if hasBase {
		if err := n.root.Overlay.Set(childPath, overlay.Deleted, false, string(baseEntry.OID)); err != nil {
			return syscall.EIO
		}
	} else if err := n.root.Overlay.Clear(childPath); err != nil {
		return syscall.EIO
	}
	return 0
}

// lookupBase resolves a repo-relative path against the base tree,
// walking one component at a time from the root.
func (n *Node) lookupBase(p string) (store.TreeEntry, error) {
	_, tree := n.root.Base()
	if p == "" {
		return store.TreeEntry{OID: tree, Type: store.TypeTree}, nil
	}
	cur := tree
	parts := splitPath(p)
	var found store.TreeEntry
	for i, part := range parts {
		entries, err := n.root.Store.Tree(cur)
		if err != nil {
			return store.TreeEntry{}, err
		}
		matched := false
		for _, e := range entries {
			if e.Name == part {
				found = e
				cur = e.OID
				matched = true
				break
			}
		}
		if !matched {
			return store.TreeEntry{}, syscall.ENOENT
		}
		if i < len(parts)-1 && found.Type != store.TypeTree {
			return store.TreeEntry{}, syscall.ENOTDIR
		}
	}
	return found, nil
}

func splitPath(p string) []string {
	var parts []string
	start := 0
	for i := 0; i < len(p); i++ {
		if p[i] == '/' {
			parts = append(parts, p[start:i])
			start = i + 1
		}
	}
	parts = append(parts, p[start:])
	return parts
}

// Rename moves a path within the tree. Directory renames are handled by
// recursively re-tagging every dirty descendant plus every base
// descendant (the latter turned into Deleted-at-old-path /
// Added-at-new-path pairs, materializing content along the way) --
// gitfs does not special-case "pure rename of an untouched base
// directory" the way EdenFS's overlay does, so a rename of a large
// unmodified base subtree is O(subtree size) rather than O(1). See
// docs/architecture.md for the trade-off.
func (n *Node) Rename(ctx context.Context, name string, newParent fs.InodeEmbedder, newName string, flags uint32) syscall.Errno {
	dstNode, ok := newParent.(*Node)
	if !ok {
		return syscall.EXDEV
	}
	oldPath := join(n.path, name)
	newPath := join(dstNode.path, newName)

	paths, errno := n.collectSubtree(oldPath)
	if errno != 0 {
		return errno
	}
	for _, p := range paths {
		rel := p[len(oldPath):]
		dst := newPath + rel
		if errno := n.moveOne(p, dst); errno != 0 {
			return errno
		}
	}

	// go-fuse reuses the same Inode (and thus the same *Node Go object)
	// across a successful rename -- it calls Inode.MvChild itself right
	// after we return, rather than issuing a fresh Lookup. If the
	// kernel had already cached this subtree's inodes, their .path
	// fields would otherwise go stale, so retag them in place here.
	if child := n.EmbeddedInode().GetChild(name); child != nil {
		retagSubtree(child, oldPath, newPath)
	}
	return 0
}

// retagSubtree rewrites the .path field of node (and, recursively, of
// every already-cached descendant Inode's Node) from having oldPath as a
// prefix to having newPath as a prefix instead.
func retagSubtree(inode *fs.Inode, oldPath, newPath string) {
	if node, ok := inode.Operations().(*Node); ok {
		node.path = newPath + node.path[len(oldPath):]
	}
	for _, child := range inode.Children() {
		retagSubtree(child, oldPath, newPath)
	}
}

// collectSubtree returns oldPath itself plus, if it is a directory, every
// descendant path (base + overlay), each still relative to the repo
// root and always starting with oldPath.
func (n *Node) collectSubtree(p string) ([]string, syscall.Errno) {
	base, err := n.lookupBase(p)
	isDirBase := err == nil && (base.Type == store.TypeTree)
	_, live, tomb := n.overlayEntry(p)

	isDir := isDirBase
	if live {
		if fi, statErr := os.Lstat(n.root.Overlay.FilePath(p)); statErr == nil {
			isDir = fi.IsDir()
		}
	}
	if err != nil && !live {
		if tomb {
			return nil, syscall.ENOENT
		}
		return nil, syscall.ENOENT
	}

	if !isDir {
		return []string{p}, 0
	}

	names := make(map[string]bool)
	if isDirBase {
		entries, terr := n.root.Store.Tree(base.OID)
		if terr != nil {
			return nil, syscall.EIO
		}
		for _, e := range entries {
			names[e.Name] = true
		}
	}
	for _, e := range n.root.Overlay.Children(p) {
		names[path.Base(e.Path)] = true
	}

	out := []string{p}
	for name := range names {
		sub, errno := n.collectSubtree(join(p, name))
		if errno != 0 && errno != syscall.ENOENT {
			return nil, errno
		}
		out = append(out, sub...)
	}
	return out, 0
}

// moveOne relocates a single path's content and overlay bookkeeping.
func (n *Node) moveOne(src, dst string) syscall.Errno {
	base, baseErr := n.lookupBase(src)
	hasBase := baseErr == nil

	_, live, _ := n.overlayEntry(src)
	if live {
		fi, err := os.Lstat(n.root.Overlay.FilePath(src))
		if err != nil {
			return fs.ToErrno(err)
		}
		if !fi.IsDir() {
			if err := n.root.Overlay.EnsureParent(dst); err != nil {
				return fs.ToErrno(err)
			}
			if err := os.Rename(n.root.Overlay.FilePath(src), n.root.Overlay.FilePath(dst)); err != nil {
				return fs.ToErrno(err)
			}
		}
		state := overlay.Modified
		oid := ""
		if hasBase {
			oid = string(base.OID)
		} else {
			state = overlay.Added
		}
		isDir := fi.IsDir()
		if isDir {
			if err := os.MkdirAll(n.root.Overlay.FilePath(dst), fi.Mode().Perm()); err != nil {
				return fs.ToErrno(err)
			}
		}
		if err := n.root.Overlay.Set(dst, state, isDir, oid); err != nil {
			return syscall.EIO
		}
	} else if hasBase {
		// Unmodified base file/dir being moved: materialize content at
		// the destination and tombstone the source.
		isDir := base.Type == store.TypeTree
		if !isDir {
			data, err := n.root.Store.Blob(base.OID)
			if err != nil {
				return fs.ToErrno(err)
			}
			if err := n.root.Overlay.EnsureParent(dst); err != nil {
				return fs.ToErrno(err)
			}
			mode := os.FileMode(0o644)
			if base.GitMode == gitModeExec {
				mode = 0o755
			}
			if err := os.WriteFile(n.root.Overlay.FilePath(dst), data, mode); err != nil {
				return fs.ToErrno(err)
			}
		}
		if err := n.root.Overlay.Set(dst, overlay.Modified, isDir, string(base.OID)); err != nil {
			return syscall.EIO
		}
	}

	if hasBase {
		if err := n.root.Overlay.Set(src, overlay.Deleted, base.Type == store.TypeTree, string(base.OID)); err != nil {
			return syscall.EIO
		}
	} else {
		if err := n.root.Overlay.RemoveFile(src); err != nil {
			return fs.ToErrno(err)
		}
		if err := n.root.Overlay.Clear(src); err != nil {
			return syscall.EIO
		}
	}
	return 0
}

// Symlink creates a real OS symlink inside the overlay.
func (n *Node) Symlink(ctx context.Context, target, name string, out *fuse.EntryOut) (*fs.Inode, syscall.Errno) {
	childPath := join(n.path, name)
	if err := n.root.Overlay.EnsureParent(childPath); err != nil {
		return nil, fs.ToErrno(err)
	}
	if err := os.Symlink(target, n.root.Overlay.FilePath(childPath)); err != nil {
		return nil, fs.ToErrno(err)
	}
	if err := n.root.Overlay.Set(childPath, overlay.Added, false, ""); err != nil {
		return nil, syscall.EIO
	}
	out.Mode = fuse.S_IFLNK | 0o777
	out.Size = uint64(len(target))
	out.Ino = ino(childPath, false)
	child := &Node{root: n.root, path: childPath, isDir: false}
	return n.NewInode(ctx, child, n.stableAttr(false, childPath)), 0
}

// Readlink returns a symlink's target, from the overlay if materialized
// or straight out of the base blob (git stores a symlink's target as the
// blob content) otherwise.
func (n *Node) Readlink(ctx context.Context) ([]byte, syscall.Errno) {
	if _, live, tomb := n.overlayEntry(n.path); tomb {
		return nil, syscall.ENOENT
	} else if live {
		target, err := os.Readlink(n.root.Overlay.FilePath(n.path))
		if err != nil {
			return nil, fs.ToErrno(err)
		}
		return []byte(target), 0
	}
	base, ok := n.resolveBase()
	if !ok {
		return nil, syscall.ENOENT
	}
	data, err := n.root.Store.Blob(base.OID)
	if err != nil {
		return nil, fs.ToErrno(err)
	}
	return data, 0
}
