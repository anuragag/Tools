// Package checkout implements gitfs's fast commit switch: moving the
// mount's base commit without re-materializing (or even re-stating) the
// whole working tree.
//
// The mechanism mirrors EdenFS's checkout path in spirit: diff the two
// tree objects to learn exactly which paths changed, touch only those,
// and leave everything else as lazily-served, un-fetched blobs. The
// difference is where the diff comes from -- EdenFS walks its own
// tree-manifest structures; gitfs asks git itself via `git diff-tree`,
// which is already the fast, well-tested implementation of "what changed
// between these two trees" that every `git log -p` and `git diff` relies
// on. See internal/store.Store.DiffTree.
package checkout

import (
	"fmt"

	"github.com/anuragag/tools/gitfs/internal/store"
	"github.com/anuragag/tools/gitfs/internal/vfs"
)

// Result summarizes the outcome of a Checkout call.
type Result struct {
	FromCommit store.OID
	ToCommit   store.OID
	// ChangedPaths lists every path git's tree diff reports as differing
	// between FromCommit and ToCommit.
	ChangedPaths []string
	// KeptLocalEdits is the subset of ChangedPaths where the overlay
	// already had a dirty entry. gitfs always keeps the local edit in
	// that case rather than attempting a merge -- coarser than `git
	// checkout`'s per-line conflict detection, but predictable: local
	// changes are never silently discarded by a checkout.
	KeptLocalEdits []string
}

// Checkout switches root's base commit to rev. Overlay-tracked local
// edits are preserved; the kernel's cached view of every path that
// changed and was NOT locally dirty gets invalidated so a subsequent
// read sees the new base content instead of a stale cached page.
func Checkout(root *vfs.Root, rev string) (*Result, error) {
	fromCommit, _ := root.Base()

	toCommit, err := root.Store.ResolveCommit(rev)
	if err != nil {
		return nil, fmt.Errorf("gitfs/checkout: resolving %q: %w", rev, err)
	}

	res := &Result{FromCommit: fromCommit, ToCommit: toCommit.OID}
	if fromCommit == toCommit.OID {
		return res, nil
	}

	if !fromCommit.IsZero() {
		changed, err := root.Store.DiffTree(fromCommit, toCommit.OID)
		if err != nil {
			return nil, fmt.Errorf("gitfs/checkout: diffing %s..%s: %w", fromCommit, toCommit.OID, err)
		}
		res.ChangedPaths = changed

		for _, p := range changed {
			if _, dirty := root.Overlay.Get(p); dirty {
				res.KeptLocalEdits = append(res.KeptLocalEdits, p)
				continue
			}
			root.Invalidate(p)
		}
	}

	root.SetBase(toCommit.OID, toCommit.Tree)
	return res, nil
}
