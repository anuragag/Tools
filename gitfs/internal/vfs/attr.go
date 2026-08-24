package vfs

import (
	"os"

	"github.com/hanwen/go-fuse/v2/fuse"

	"github.com/anuragag/tools/gitfs/internal/store"
)

func fillEntryFromLstat(out *fuse.EntryOut, fi os.FileInfo, root *Root) {
	out.Attr = *fuse.ToAttr(fi)
	if root.UID != 0 || root.GID != 0 {
		out.Owner = fuse.Owner{Uid: root.UID, Gid: root.GID}
	}
}

func fillEntryFromGit(out *fuse.EntryOut, e store.TreeEntry, root *Root) error {
	switch {
	case e.Type == store.TypeTree || e.Type == store.TypeCommit:
		out.Mode = fuse.S_IFDIR | 0o755
	case e.GitMode == gitModeSymlink:
		data, err := root.Store.Blob(e.OID)
		if err != nil {
			return err
		}
		out.Mode = fuse.S_IFLNK | 0o777
		out.Size = uint64(len(data))
	default:
		mode := uint32(0o644)
		if e.GitMode == gitModeExec {
			mode = 0o755
		}
		out.Mode = fuse.S_IFREG | mode
		st, err := root.Store.Stat(e.OID)
		if err != nil {
			return err
		}
		out.Size = uint64(st.Size)
	}
	if root.UID != 0 || root.GID != 0 {
		out.Owner = fuse.Owner{Uid: root.UID, Gid: root.GID}
	}
	return nil
}
