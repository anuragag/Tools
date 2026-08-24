// Package overlay implements gitfs's copy-on-write layer: the local,
// mutable state that sits on top of the read-only view of a git commit
// served by internal/store.
//
// The central design choice is that the overlay only ever remembers
// *dirty* paths. A file that matches its base commit exactly is simply
// absent from the overlay index. This is what makes `gitfs status`
// O(number of local changes) instead of O(size of repository): EdenFS
// gets the same property from a similar "journal of what changed since
// the last checkpoint" design, in place of the stat-every-file loop a
// plain working directory forces on `git status`.
package overlay

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
)

// State describes how a path differs from the overlay's base commit.
type State string

const (
	// Modified means the path exists in the base tree and has been
	// overwritten with different content locally.
	Modified State = "modified"
	// Added means the path does not exist in the base tree.
	Added State = "added"
	// Deleted means the path exists in the base tree but has been
	// removed locally. It is a tombstone: it must be tracked (not just
	// omitted) so the merged view knows to hide the base entry.
	Deleted State = "deleted"
)

// Entry is one dirty path tracked by the overlay.
type Entry struct {
	Path    string // repo-relative, slash-separated, no leading slash
	State   State
	IsDir   bool
	BaseOID string // base git blob OID; empty for Added
}

// Overlay is the local write layer for one gitfs mount. It is safe for
// concurrent use by multiple FUSE request goroutines.
type Overlay struct {
	filesDir string // holds materialized content, mirroring repo-relative paths
	logPath  string

	mu      sync.RWMutex
	entries map[string]*Entry
	log     *os.File
	// dirty counts log records appended since the last Compact, to
	// decide when a background compaction is worth doing.
	dirtySinceCompact int
}

type logRecord struct {
	Op      string `json:"op"` // "set" or "clear"
	Path    string `json:"path"`
	State   State  `json:"state,omitempty"`
	IsDir   bool   `json:"is_dir,omitempty"`
	BaseOID string `json:"base_oid,omitempty"`
}

// Open loads (or creates) the overlay rooted at dir. dir will contain a
// "files" subdirectory with materialized content and a "log" file
// recording the dirty-path index.
func Open(dir string) (*Overlay, error) {
	filesDir := filepath.Join(dir, "files")
	if err := os.MkdirAll(filesDir, 0o755); err != nil {
		return nil, fmt.Errorf("gitfs/overlay: creating %s: %w", filesDir, err)
	}

	o := &Overlay{
		filesDir: filesDir,
		logPath:  filepath.Join(dir, "log"),
		entries:  make(map[string]*Entry),
	}

	if err := o.replay(); err != nil {
		return nil, err
	}

	f, err := os.OpenFile(o.logPath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return nil, fmt.Errorf("gitfs/overlay: opening log: %w", err)
	}
	o.log = f
	return o, nil
}

func (o *Overlay) replay() error {
	f, err := os.Open(o.logPath)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("gitfs/overlay: reading log: %w", err)
	}
	defer f.Close()

	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 64*1024), 8<<20)
	for sc.Scan() {
		line := sc.Bytes()
		if len(line) == 0 {
			continue
		}
		var rec logRecord
		if err := json.Unmarshal(line, &rec); err != nil {
			return fmt.Errorf("gitfs/overlay: corrupt log line: %w", err)
		}
		switch rec.Op {
		case "set":
			o.entries[rec.Path] = &Entry{Path: rec.Path, State: rec.State, IsDir: rec.IsDir, BaseOID: rec.BaseOID}
		case "clear":
			delete(o.entries, rec.Path)
		}
	}
	return sc.Err()
}

func (o *Overlay) appendLocked(rec logRecord) error {
	b, err := json.Marshal(rec)
	if err != nil {
		return err
	}
	b = append(b, '\n')
	if _, err := o.log.Write(b); err != nil {
		return fmt.Errorf("gitfs/overlay: appending log: %w", err)
	}
	o.dirtySinceCompact++
	return nil
}

// Set records path as dirty with the given state.
func (o *Overlay) Set(path string, state State, isDir bool, baseOID string) error {
	path = clean(path)
	o.mu.Lock()
	defer o.mu.Unlock()
	if err := o.appendLocked(logRecord{Op: "set", Path: path, State: state, IsDir: isDir, BaseOID: baseOID}); err != nil {
		return err
	}
	o.entries[path] = &Entry{Path: path, State: state, IsDir: isDir, BaseOID: baseOID}
	return nil
}

// Clear removes path from the dirty index entirely (it is once again
// considered to exactly match the base commit, or to never have existed).
func (o *Overlay) Clear(path string) error {
	path = clean(path)
	o.mu.Lock()
	defer o.mu.Unlock()
	if _, ok := o.entries[path]; !ok {
		return nil
	}
	if err := o.appendLocked(logRecord{Op: "clear", Path: path}); err != nil {
		return err
	}
	delete(o.entries, path)
	return nil
}

// Get returns the dirty entry for path, if any.
func (o *Overlay) Get(path string) (Entry, bool) {
	path = clean(path)
	o.mu.RLock()
	defer o.mu.RUnlock()
	e, ok := o.entries[path]
	if !ok {
		return Entry{}, false
	}
	return *e, true
}

// List returns all dirty entries, sorted by path. This is the entire
// working set `gitfs status` needs to look at.
func (o *Overlay) List() []Entry {
	o.mu.RLock()
	defer o.mu.RUnlock()
	out := make([]Entry, 0, len(o.entries))
	for _, e := range o.entries {
		out = append(out, *e)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Path < out[j].Path })
	return out
}

// Children returns the dirty entries whose path's immediate parent
// directory is dir ("" for the repo root).
func (o *Overlay) Children(dir string) []Entry {
	dir = clean(dir)
	o.mu.RLock()
	defer o.mu.RUnlock()
	var out []Entry
	for _, e := range o.entries {
		if parentOf(e.Path) == dir {
			out = append(out, *e)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Path < out[j].Path })
	return out
}

// HasDirtyDescendant reports whether any tracked entry lives under dir
// (used to decide whether an otherwise-empty base directory must still
// be materialized because a file below it was added or modified).
func (o *Overlay) HasDirtyDescendant(dir string) bool {
	dir = clean(dir)
	prefix := dir + "/"
	o.mu.RLock()
	defer o.mu.RUnlock()
	for p := range o.entries {
		if dir == "" || strings.HasPrefix(p, prefix) {
			return true
		}
	}
	return false
}

// FilePath returns the absolute path of the materialized file for path
// within the overlay's local storage. The caller is responsible for
// creating parent directories before writing (see EnsureParent).
func (o *Overlay) FilePath(path string) string {
	return filepath.Join(o.filesDir, filepath.FromSlash(clean(path)))
}

// EnsureParent creates the on-disk parent directory for path within the
// overlay's file storage.
func (o *Overlay) EnsureParent(path string) error {
	return os.MkdirAll(filepath.Dir(o.FilePath(path)), 0o755)
}

// RemoveFile deletes the materialized file for path, if any. It is not
// an error for the file to already be absent (e.g. a path that was
// Added then Cleared without ever being opened for write).
func (o *Overlay) RemoveFile(path string) error {
	err := os.Remove(o.FilePath(path))
	if err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}

// Compact rewrites the log to contain exactly one "set" record per
// currently-dirty path, dropping history. Safe to call while other
// goroutines are reading/writing entries.
func (o *Overlay) Compact() error {
	o.mu.Lock()
	defer o.mu.Unlock()

	tmpPath := o.logPath + ".compact"
	tmp, err := os.OpenFile(tmpPath, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
	if err != nil {
		return fmt.Errorf("gitfs/overlay: compact: %w", err)
	}
	w := bufio.NewWriter(tmp)
	paths := make([]string, 0, len(o.entries))
	for p := range o.entries {
		paths = append(paths, p)
	}
	sort.Strings(paths)
	for _, p := range paths {
		e := o.entries[p]
		b, err := json.Marshal(logRecord{Op: "set", Path: e.Path, State: e.State, IsDir: e.IsDir, BaseOID: e.BaseOID})
		if err != nil {
			tmp.Close()
			return err
		}
		w.Write(b)
		w.WriteByte('\n')
	}
	if err := w.Flush(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}

	if err := o.log.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmpPath, o.logPath); err != nil {
		return err
	}
	f, err := os.OpenFile(o.logPath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	o.log = f
	o.dirtySinceCompact = 0
	return nil
}

// DirtySinceCompact reports how many log records have been appended
// since the last compaction, so callers can decide when to compact.
func (o *Overlay) DirtySinceCompact() int {
	o.mu.RLock()
	defer o.mu.RUnlock()
	return o.dirtySinceCompact
}

// Close flushes and closes the overlay's log file. It does not delete
// any materialized content.
func (o *Overlay) Close() error {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.log.Close()
}

func clean(path string) string {
	path = filepath.ToSlash(path)
	path = strings.TrimPrefix(path, "/")
	return strings.TrimSuffix(path, "/")
}

func parentOf(path string) string {
	i := strings.LastIndexByte(path, '/')
	if i < 0 {
		return ""
	}
	return path[:i]
}
