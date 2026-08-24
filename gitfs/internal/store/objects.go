package store

import (
	"bytes"
	"fmt"
	"io/fs"
)

// TreeEntry is one line of a git tree object.
type TreeEntry struct {
	Name string
	OID  OID
	Type ObjectType // blob, tree, or commit (gitlink/submodule)
	Mode fs.FileMode
	// GitMode is the raw octal mode string as git stores it (e.g.
	// "100644", "100755", "120000", "40000", "160000"), kept around
	// because fs.FileMode can't round-trip a gitlink cleanly.
	GitMode string
}

const (
	gitModeDir     = "40000"
	gitModeFile    = "100644"
	gitModeExec    = "100755"
	gitModeSymlink = "120000"
	gitModeGitlink = "160000"
)

func modeToFS(gitMode string) fs.FileMode {
	switch gitMode {
	case gitModeDir:
		return fs.ModeDir | 0o755
	case gitModeExec:
		return 0o755
	case gitModeSymlink:
		return fs.ModeSymlink | 0o777
	case gitModeGitlink:
		return fs.ModeIrregular | 0o644
	default:
		return 0o644
	}
}

// parseTree decodes a git tree object's raw content (as returned by
// `cat-file --batch`, i.e. with the "tree <n>\0" header already stripped).
//
// Format: a sequence of entries, each
//
//	<mode ascii> ' ' <name> '\0' <hash bytes, raw binary>
//
// repeated until the buffer is exhausted. hashLen is the object hash size
// in bytes (20 for sha1, 32 for sha256); the caller determines it once per
// repository via Store.hashLen.
func parseTree(content []byte, hashLen int) ([]TreeEntry, error) {
	var entries []TreeEntry
	buf := content
	for len(buf) > 0 {
		sp := bytes.IndexByte(buf, ' ')
		if sp < 0 {
			return nil, fmt.Errorf("gitfs/store: malformed tree entry (no space)")
		}
		mode := string(buf[:sp])
		buf = buf[sp+1:]

		nul := bytes.IndexByte(buf, 0)
		if nul < 0 {
			return nil, fmt.Errorf("gitfs/store: malformed tree entry (no NUL)")
		}
		name := string(buf[:nul])
		buf = buf[nul+1:]

		if len(buf) < hashLen {
			return nil, fmt.Errorf("gitfs/store: malformed tree entry (short hash)")
		}
		oid := OID(fmt.Sprintf("%x", buf[:hashLen]))
		buf = buf[hashLen:]

		typ := TypeBlob
		switch mode {
		case gitModeDir:
			typ = TypeTree
		case gitModeGitlink:
			typ = TypeCommit
		}

		entries = append(entries, TreeEntry{
			Name:    name,
			OID:     oid,
			Type:    typ,
			Mode:    modeToFS(mode),
			GitMode: normalizeMode(mode),
		})
	}
	return entries, nil
}

// normalizeMode pads the ascii mode to the canonical 6-digit form git
// itself uses in plumbing output ("40000" -> "040000") so downstream code
// can compare modes as plain strings.
func normalizeMode(mode string) string {
	if len(mode) == 5 {
		return "0" + mode
	}
	return mode
}

// Commit is the subset of a git commit object gitfs needs.
type Commit struct {
	OID     OID
	Tree    OID
	Parents []OID
}

func parseCommit(oid OID, content []byte) (*Commit, error) {
	c := &Commit{OID: oid}
	lines := bytes.Split(content, []byte("\n"))
	for _, line := range lines {
		if len(line) == 0 {
			break // blank line ends the header section
		}
		fieldEnd := bytes.IndexByte(line, ' ')
		if fieldEnd < 0 {
			continue
		}
		field := string(line[:fieldEnd])
		value := string(line[fieldEnd+1:])
		switch field {
		case "tree":
			c.Tree = OID(value)
		case "parent":
			c.Parents = append(c.Parents, OID(value))
		}
	}
	if c.Tree.IsZero() {
		return nil, fmt.Errorf("gitfs/store: commit %s has no tree line", oid)
	}
	return c, nil
}

// hashLenFromOID infers the hash length in bytes from a hex OID's string
// length, used as a fallback when Store hasn't queried the repo's object
// format yet.
func hashLenFromOID(oid OID) int {
	return len(oid) / 2
}
