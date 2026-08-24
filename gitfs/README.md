# gitfs

A git-native, EdenFS-inspired virtual filesystem: mount a git commit as a
working directory without checking it out first, with fast status and
fast checkout, designed to run inside a [gVisor](https://gvisor.dev)
sandbox for agentic coding workloads.

```
gitfsd mount /path/to/repo.git /workspace   # instant, lazy checkout
gitfsd status /workspace                    # O(local changes), not O(repo size)
gitfsd checkout /workspace <rev>            # switches base commit, keeps local edits
gitfsd unmount /workspace
```

## Why

Agent sandboxes want a working copy of a (sometimes large) repository,
want to switch commits/branches cheaply, and want `status`-like
operations to stay fast regardless of repo size. A normal `git
checkout` pays for all of that up front (every blob written to disk)
and `git status` pays an O(repo size) stat-every-file cost on every
call. EdenFS solved this for large monorepos with a lazy, virtual
working copy; gitfs re-derives the same idea scoped to "mount one git
repo, fast, inside a sandbox" and built entirely on git's own plumbing
(`git cat-file`, `git diff-tree`) instead of a custom backing-store
service.

Read [docs/architecture.md](docs/architecture.md) for the full design
and its trade-offs against EdenFS, and
[docs/gvisor.md](docs/gvisor.md) for how to run it inside a gVisor
sandbox.

## How it works, briefly

- **Reads are lazy.** A file's content is fetched from git only when
  something opens it; `ls`/`stat` never do more than a cheap
  `git cat-file --batch-check` size probe. Nothing is written to local
  disk until something writes to it.
- **Writes go to a local overlay**, never to the source repository.
  gitfs tracks only *dirty* paths, so `gitfs status` is a direct read of
  that index -- O(number of local changes), never O(repository size).
- **Checkout is a tree diff, not a re-materialization.** Switching
  commits asks `git diff-tree` what changed and invalidates only those
  kernel-cached paths; everything else stays exactly as lazily-served as
  it was. Local edits are always kept, never silently overwritten.
- **Laziness comes from git's own partial-clone support**, not a custom
  fetch protocol: point gitfs at a `git clone --filter=blob:none` clone
  and blob fetches happen on demand through the same `git cat-file`
  calls gitfs already makes.

## Layout

```
cmd/gitfsd/          CLI + daemon (mount / status / checkout / unmount)
internal/store/      git plumbing backing store (cat-file, diff-tree)
internal/overlay/    local copy-on-write layer + dirty-path index
internal/vfs/        FUSE surface (github.com/hanwen/go-fuse)
internal/status/     fast status report from the overlay index
internal/checkout/   base-commit switch + kernel cache invalidation
internal/ctl/        control-socket protocol between the daemon and the CLI
internal/statedir/   per-mount state directory layout
docs/                architecture and gVisor integration notes
deploy/Dockerfile    minimal image for running gitfsd in a container
scripts/demo.sh      end-to-end CLI walkthrough against a throwaway repo
```

## Building and testing

```bash
go build ./...
go test ./...          # includes real FUSE mounts and real git subprocesses;
                        # tests skip gracefully if the sandbox refuses mount(2)
./scripts/demo.sh       # manual end-to-end walkthrough
```

Requires `git` on `PATH` and, for the FUSE-mounting tests/commands,
`/dev/fuse` plus permission to call `mount(2)` (root, or `CAP_SYS_ADMIN`
in a container).

## CLI

```
gitfsd mount [flags] <repo> <mountpoint>
    --ref string          git revision to check out (default "HEAD")
    --overlay string      overlay directory (default: derived per-mount state dir)
    --allow-other          allow other users/processes to access the mount (default true)
    --debug                 log every FUSE operation

gitfsd status <mountpoint>
gitfsd checkout <mountpoint> <rev>
gitfsd unmount <mountpoint>
```

`<repo>` may be a working-tree path or a bare/mirror repository; gitfsd
resolves the real `GIT_DIR` itself. `mount` runs in the foreground and
serves the filesystem until it receives SIGINT/SIGTERM or an `unmount`
request through its control socket.

## Known limitations

See "Known limitations" in
[docs/architecture.md](docs/architecture.md#known-limitations) --
notably, there's no `.git` exposed *inside* the mount today, so real
`git` commands should be run against the source repository directly
rather than through the FUSE-mounted path; `gitfsd status`/`checkout`
are the fast-path equivalents for the mount itself.
