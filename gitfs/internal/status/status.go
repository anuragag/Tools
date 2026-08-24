// Package status computes gitfs's fast status report: the set of paths
// that differ from the checked-out base commit.
//
// The defining property is that Report costs O(number of local changes),
// never O(repository size). It gets that by trusting the overlay's dirty
// index completely rather than re-verifying anything against disk or
// against git: every write, create, delete, or rename that ever touched
// the mount already updated that index in internal/overlay, so this
// package just reads it back out. A plain `git status` on a large repo
// instead has to stat (and for some states, hash) every tracked file,
// because a normal working directory carries no record of what changed
// -- this is precisely the cost EdenFS's overlay-journal design (and
// gitfs's overlay index) exists to avoid.
package status

import (
	"fmt"
	"strings"

	"github.com/anuragag/tools/gitfs/internal/overlay"
)

// Kind is the single-letter classification of a dirty path, chosen to
// read the same way as `git status --short`'s worktree column.
type Kind byte

const (
	Modified Kind = 'M'
	Added    Kind = 'A'
	Deleted  Kind = 'D'
)

func kindOf(s overlay.State) Kind {
	switch s {
	case overlay.Added:
		return Added
	case overlay.Deleted:
		return Deleted
	default:
		return Modified
	}
}

// Change is one dirty path.
type Change struct {
	Path string
	Kind Kind
}

// Report returns every dirty path, sorted, classified by how it differs
// from the base commit. gitfs has no staging area of its own -- every
// entry here is the moral equivalent of an unstaged git change; running
// `git add`/`git commit` directly against the mounted files still works
// exactly as it would on a normal checkout.
func Report(ovl *overlay.Overlay) []Change {
	entries := ovl.List()
	changes := make([]Change, len(entries))
	for i, e := range entries {
		changes[i] = Change{Path: e.Path, Kind: kindOf(e.State)}
	}
	return changes
}

// FormatShort renders changes the way `git status --short` would, one
// line per path: a single status letter, a space, then the path.
func FormatShort(changes []Change) string {
	var b strings.Builder
	for _, c := range changes {
		fmt.Fprintf(&b, "%c  %s\n", c.Kind, c.Path)
	}
	return b.String()
}

// CountByKind tallies changes per Kind, letting callers print a one-line
// summary ("3 modified, 1 added, 1 deleted") without a second pass.
func CountByKind(changes []Change) map[Kind]int {
	counts := make(map[Kind]int, 3)
	for _, c := range changes {
		counts[c.Kind]++
	}
	return counts
}

// sortedKinds is a stable display order for summaries.
var sortedKinds = []Kind{Added, Modified, Deleted}

// Summary renders a one-line human summary, e.g. "2 modified, 1 added".
func Summary(changes []Change) string {
	if len(changes) == 0 {
		return "clean"
	}
	counts := CountByKind(changes)
	names := map[Kind]string{Added: "added", Modified: "modified", Deleted: "deleted"}
	var parts []string
	for _, k := range sortedKinds {
		if n := counts[k]; n > 0 {
			parts = append(parts, fmt.Sprintf("%d %s", n, names[k]))
		}
	}
	return strings.Join(parts, ", ")
}
