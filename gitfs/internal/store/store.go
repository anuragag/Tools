package store

import (
	"fmt"
	"os/exec"
	"runtime"
	"strings"
)

// Store is a read-only, concurrency-safe view onto a git repository's
// object database. It never mutates the repository: writes made through
// gitfs live entirely in the overlay (see internal/overlay) and are only
// ever turned into git objects by the user's normal `git add`/`git
// commit`, run against the real working tree gitfs exposes.
//
// Because Store defers all object retrieval to `git cat-file`, it
// transparently benefits from whatever the repository was cloned with:
// a full clone serves every read from local disk, while a partial clone
// (`git clone --filter=blob:none` or `--filter=tree:0`) causes git itself
// to fetch missing objects on demand from the promisor remote the first
// time gitfs asks for them. That is the mechanism that gives gitfs
// EdenFS-style "checkout is instant, bytes show up as you touch files"
// behavior without gitfs having to speak a custom network protocol.
type Store struct {
	gitDir  string
	pool    *batchPool
	checks  *checkPool
	hashLen int
	cache   *blobCache
	trees   *treeCache
}

// Options configures a Store.
type Options struct {
	// Concurrency is the number of parallel `git cat-file --batch`
	// subprocesses to keep warm. Defaults to GOMAXPROCS, capped at 8.
	Concurrency int
	// BlobCacheBytes bounds how much decoded blob content Store keeps
	// in memory. Defaults to 256 MiB. Set to 0 to disable caching.
	BlobCacheBytes int64
	// TreeCacheEntries bounds the total number of TreeEntry values kept
	// across all cached (immutable, content-addressed) tree objects.
	// Defaults to 200,000.
	TreeCacheEntries int
}

func (o Options) withDefaults() Options {
	if o.Concurrency <= 0 {
		o.Concurrency = runtime.GOMAXPROCS(0)
		if o.Concurrency > 8 {
			o.Concurrency = 8
		}
	}
	if o.BlobCacheBytes == 0 {
		o.BlobCacheBytes = 256 << 20
	}
	if o.TreeCacheEntries == 0 {
		o.TreeCacheEntries = 200_000
	}
	return o
}

// Open starts the backing subprocess pool for the git repository whose
// GIT_DIR is gitDir (the ".git" directory of a normal checkout, or the
// repo directory itself for a bare/mirror clone).
func Open(gitDir string, opts Options) (*Store, error) {
	opts = opts.withDefaults()

	if out, err := exec.Command("git", "--git-dir", gitDir, "rev-parse", "--git-dir").CombinedOutput(); err != nil {
		return nil, fmt.Errorf("gitfs/store: %s is not a git repository: %v: %s", gitDir, err, strings.TrimSpace(string(out)))
	}

	pool, err := newBatchPool(gitDir, opts.Concurrency)
	if err != nil {
		return nil, err
	}
	checks, err := newCheckPool(gitDir, opts.Concurrency)
	if err != nil {
		_ = pool.close()
		return nil, err
	}

	hashLen := 20
	if out, err := exec.Command("git", "--git-dir", gitDir, "rev-parse", "--show-object-format").CombinedOutput(); err == nil {
		switch strings.TrimSpace(string(out)) {
		case "sha256":
			hashLen = 32
		}
	}

	return &Store{
		gitDir:  gitDir,
		pool:    pool,
		checks:  checks,
		hashLen: hashLen,
		cache:   newBlobCache(opts.BlobCacheBytes),
		trees:   newTreeCache(opts.TreeCacheEntries),
	}, nil
}

// Close terminates the backing git subprocesses.
func (s *Store) Close() error {
	err1 := s.pool.close()
	err2 := s.checks.close()
	if err1 != nil {
		return err1
	}
	return err2
}

// Stat fetches an object's type and size without transferring its
// content, using `git cat-file --batch-check`. This is the method
// Getattr should use: it lets `ls -la` and similar stay cheap even on
// directories full of large files, since git only needs to consult its
// object index (or, for a promisor remote, a lightweight size probe)
// rather than reading and shipping the whole blob.
func (s *Store) Stat(oid OID) (Stat, error) {
	return s.checks.stat(string(oid))
}

// ResolveCommit resolves any git revision expression to a commit.
func (s *Store) ResolveCommit(rev string) (*Commit, error) {
	obj, err := s.pool.get(rev + "^{commit}")
	if err != nil {
		return nil, err
	}
	if obj.Type != TypeCommit {
		return nil, &ErrWrongType{Ref: rev, Want: TypeCommit, Got: obj.Type}
	}
	return parseCommit(obj.OID, obj.Content)
}

// Tree fetches and parses a tree object.
func (s *Store) Tree(oid OID) ([]TreeEntry, error) {
	if entries, ok := s.trees.get(oid); ok {
		return entries, nil
	}
	obj, err := s.pool.get(string(oid))
	if err != nil {
		return nil, err
	}
	if obj.Type != TypeTree {
		return nil, &ErrWrongType{Ref: string(oid), Want: TypeTree, Got: obj.Type}
	}
	hashLen := s.hashLen
	if hashLen == 0 {
		hashLen = hashLenFromOID(obj.OID)
	}
	entries, err := parseTree(obj.Content, hashLen)
	if err != nil {
		return nil, err
	}
	s.trees.put(oid, entries)
	return entries, nil
}

// Blob fetches a blob's full content, transparently caching it.
func (s *Store) Blob(oid OID) ([]byte, error) {
	if data, ok := s.cache.get(oid); ok {
		return data, nil
	}
	obj, err := s.pool.get(string(oid))
	if err != nil {
		return nil, err
	}
	if obj.Type != TypeBlob {
		return nil, &ErrWrongType{Ref: string(oid), Want: TypeBlob, Got: obj.Type}
	}
	s.cache.put(oid, obj.Content)
	return obj.Content, nil
}

// DiffTree returns the set of paths (relative to the repo root) whose
// content or mode differs between two commits, using git's own tree
// differ. gitfs uses this during checkout to invalidate only the FUSE
// nodes that actually changed instead of dropping and re-walking the
// whole tree.
func (s *Store) DiffTree(from, to OID) ([]string, error) {
	cmd := exec.Command("git", "--git-dir", s.gitDir, "diff-tree", "-r", "--name-only", "--no-commit-id", string(from), string(to))
	out, err := cmd.Output()
	if err != nil {
		return nil, fmt.Errorf("gitfs/store: diff-tree %s..%s: %w", from, to, err)
	}
	trimmed := strings.TrimSpace(string(out))
	if trimmed == "" {
		return nil, nil
	}
	return strings.Split(trimmed, "\n"), nil
}
