# gitfs architecture

gitfs is a FUSE filesystem that presents a git commit as a mountable
working directory without a `git checkout` first materializing every
file, and without a plain working directory's O(repo size) `git status`
cost. It borrows EdenFS's central idea -- a lazy, virtual working copy
backed by an overlay of local edits -- and re-derives it around git's own
plumbing instead of building a parallel backing-store protocol.

## Why not just use EdenFS?

EdenFS is a real, production-grade implementation of this idea, but it
carries assumptions gitfs doesn't want to inherit for this use case:

- It's built primarily for Mercurial (`remotefilelog`) with git support
  added later via `libgit2`; its lazy-fetch protocol is its own Thrift
  service talking to a Mononoke/EdenAPI-shaped backend.
- It's a long-lived, multi-repo system daemon (`edenfs`) with a service
  manager, a Thrift control plane, and a C++/Rust codebase -- appropriate
  for a company-wide source control fleet, heavy for "give one agent
  sandbox a fast checkout of one repo."
- It assumes a persistent host. An agent sandbox is often ephemeral: the
  whole point is to boot, do work, and throw the container away.

gitfs instead leans entirely on `git` itself as the backing store (see
below), ships as a single static-ish Go binary, and is scoped to one job:
mount one repo, lazily, with fast status and checkout, inside a sandbox.

## The four layers

```
                         FUSE (kernel) <-- syscalls from processes
                              |
                        internal/vfs
                    (go-fuse Node: Lookup, Readdir,
                     Getattr, Open, Read, Write, ...)
                        /              \
        internal/store                  internal/overlay
   (read-only, lazy, content-      (read-write, local-only,
    addressed, backed by git        tracks only dirty paths)
    object plumbing)
```

**internal/store** wraps a pool of long-lived `git cat-file --batch` /
`--batch-check` subprocesses. It never re-implements git's object
format, pack decoding, or delta resolution -- it asks git for exactly
the object it needs (a tree listing, a blob's bytes, a commit's tree
pointer) and lets git's own code paths do the rest. `git diff-tree -r`
does the same job for computing what changed between two commits.

**internal/overlay** is the copy-on-write layer. Its defining property:
it only ever records *dirty* paths. A file that matches the base commit
exactly is simply absent from the overlay's index -- there is no entry
to clean up, nothing to reconcile. This is what makes `gitfs status`
O(local changes) rather than O(repository size): a plain working
directory carries no memory of what changed, so `git status` there has
to stat (and sometimes hash) every tracked file to find out.

**internal/vfs** is the FUSE surface (built on
[hanwen/go-fuse](https://github.com/hanwen/go-fuse)). Every node
resolves its identity on demand: a `Lookup` for one file fetches (and
Store-caches) exactly one tree object, never a whole subtree; a file's
content is fetched from git only when something actually opens it for
reading; a write promotes exactly that one file into the overlay,
nothing else. Directory nodes never cache "my base tree is OID X" --
they re-resolve it from the live root on every operation via
`Node.resolveBase`, which is what makes checkout correctness simple
(see below) instead of requiring the daemon to walk the kernel's cached
inode tree and patch every stale OID by hand.

**internal/checkout** switches the mount's base commit. It diffs the old
and new tree with `git diff-tree`, which is already the well-tested
"what changed between these two trees" git relies on for `git diff` and
`git log -p`. For every changed path with no local (overlay) edit, it
tells the kernel to drop its cached dentry/page-cache for that path
(`Inode.NotifyEntry` / `NotifyContent`); paths the overlay is already
tracking keep the local edit untouched. Because directory nodes never
cache a stale tree OID (see above), nothing needs the FUSE-tree-walking
"find every cached inode below this changed path and patch its base
OID" logic that a per-node-cached design would require.

## Laziness comes from git, not from gitfs

EdenFS's laziness depends on a custom backing-store RPC (originally for
Mercurial's remotefilelog, later adapted for Mononoke/EdenAPI) that
fetches individual blobs/trees on demand from a service built for that
purpose. git already has an equivalent mechanism: **partial clone**.

Clone with `--filter=blob:none` (or `--filter=tree:0`) and the resulting
repository is a normal git repository whose promisor remote git
transparently consults whenever a local object is missing -- including
from inside `git cat-file --batch`, which is exactly what
internal/store drives. gitfs doesn't have to know or care whether a
given repo is a full clone or a partial one; either way, the first
`cat-file` request for a given blob is what pulls its bytes down, and
every subsequent request is served from the local object database (plus
gitfs's own in-memory blob/tree LRU caches on top of that).

This has one real consequence worth calling out: git's object model is
whole-object granular. There's no "fetch bytes 4096-8192 of this 500MB
blob" the way EdenFS's chunked backing store can do. gitfs's Store
caches and serves whole blobs; for the source-tree-sized files agent
workloads typically touch this is the right trade, but it's a
deliberate scoping decision, not an oversight.

## Fast status, honestly

`gitfs status` (see internal/status) is not a hash-verified diff against
HEAD -- it's a direct read of the overlay's dirty-path index. Every
write, create, delete, and rename that ever touched the mount already
updated that index at the time it happened, so status is a single
`O(dirty)` pass with no stat calls, no content hashing, and no tree walk
at read time. This matches EdenFS's own approach (its overlay maintains
a similar journal) and is the same reason `git status` is slow on huge
working directories: a plain checkout has nowhere to record "what
changed" except by re-deriving it from scratch on every invocation.

The trade-off: gitfs trusts that every mutating FUSE operation correctly
updated the index (see internal/overlay's tests) rather than
independently re-verifying content against the base commit. A `git add
-p` / `git commit` run directly against the mounted files still produces
a completely normal, correct commit -- gitfs's overlay index is not a
substitute for git's own index, just a fast local record of "what's
different," and the underlying bytes on disk are always real,
byte-correct file content, never a synthetic diff format.

## Checkout, and what "keep local edits" means

`gitfs checkout <rev>` (see internal/checkout) is deliberately coarser
than `git checkout`'s per-line conflict detection: if a path both
changed between the old and new commit *and* has a local (overlay)
edit, gitfs always keeps the local edit and leaves that path dirty
against the new base. It never attempts a three-way merge and it never
silently discards a local change. This is a simpler, safer default for
an agent loop that might checkout a different ref mid-task -- but it
means gitfs's checkout does not detect the subset of conflicts that
`git checkout`'s worktree safety check would catch (e.g., overwriting a
local edit) and does not report them as conflicts; it reports them as
`KeptLocalEdits` in `checkout.Result` for the caller to inspect.

## Known limitations

- **No `.git` inside the mount.** gitfs currently exposes only the
  working-tree content, not a git index/HEAD/refs directory, so real
  `git status`/`git add`/`git commit` run *inside* the mounted directory
  won't find a repository. Run `gitfsd status`/`gitfsd checkout`
  against the mount from outside, or run real `git` commands against
  the original repository directly. A `git worktree`-style `.git` file
  redirect (`gitdir: <path>`, registered via `git worktree add
  --no-checkout` against the source repo before mounting) is the
  natural way to close this gap, deliberately left as follow-up work
  rather than half-implemented here -- getting the worktree
  registration, index locking, and HEAD tracking right without risking
  corruption of the source repository's own checkout is a bigger, more
  careful piece of work than this MVP's scope.
- **Renaming an untouched base directory is O(subtree size).** gitfs
  doesn't special-case "pure rename of an unmodified base subtree" the
  way EdenFS's overlay can (by just relabeling a path prefix); it
  materializes every file in the subtree into the overlay. Renaming
  individual files, and renaming directories that already have local
  edits, is cheap; renaming a large never-touched directory is not.
- **Directory content granularity for symlinks/executables** comes
  entirely from the git tree entry's mode bits, applied at Lookup/
  Getattr time; there's no extended-attribute or ACL support beyond
  standard POSIX mode bits.
- **Submodules (gitlinks)** show up as empty directories; gitfs does not
  fetch or mount submodule content.
