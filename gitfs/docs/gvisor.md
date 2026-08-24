# Running gitfs inside a gVisor sandbox

gitfs is meant to sit directly inside the sandbox an agentic coding
workload runs in: the agent's container mounts a repository through
`gitfsd`, works against it with normal file I/O and normal `git`
commands run against the source repo, and gitfs keeps checkout/status
fast without materializing the whole tree first.

## Which gVisor FUSE mode this needs

gVisor (`runsc`) supports two FUSE modes ([gVisor FUSE
docs](https://gvisor.dev/docs/user_guide/fuse/)):

- **In-sandbox FUSE** -- a FUSE daemon runs *inside* the sandbox, opens
  `/dev/fuse` itself, and mounts through it exactly like FUSE on a
  normal Linux host. No special `runsc` flags or host-side setup.
- **External FUSE server** -- a *host-side* process serves the
  filesystem into the sandbox over a passed socketpair FD
  (`--pass-fd`), speaking the raw kernel FUSE wire protocol directly
  instead of using `/dev/fuse`.

**gitfsd is designed for the in-sandbox mode.** It's a normal
`hanwen/go-fuse`-based FUSE daemon: it opens `/dev/fuse` and issues the
mount itself (with `DirectMount: true`, i.e. via the `mount(2)` syscall
rather than shelling out to the `fusermount` SUID helper, since agent
sandboxes typically run as root without `fusermount` installed at all).
gVisor's sentry intercepts that `mount(2)` call and the subsequent
`/dev/fuse` read/write traffic the same way it intercepts every other
syscall; nothing about gitfsd needs to know it's running under gVisor
instead of a normal kernel.

The external-server mode is a different integration shape (gitfsd would
have to speak the raw kernel FUSE protocol over a passed FD instead of
using `/dev/fuse`) and isn't what this repo implements.

## Container requirements

Run gitfsd's container the way you'd run any FUSE-using container:

- **`/dev/fuse` available** in the sandbox. Docker/`runsc` expose this
  automatically for a privileged-enough container; if you're
  constructing the OCI spec by hand, make sure `/dev/fuse` is in the
  container's device list.
- **`CAP_SYS_ADMIN`** (or run as root, which is the common case for
  agent sandboxes already) -- needed to call `mount(2)` for the FUSE
  filesystem, same as on a normal host.
- **No `fusermount` binary required.** gitfsd mounts directly via the
  `mount(2)` syscall (go-fuse's `DirectMount` option), so a minimal
  container image doesn't need `fuse`/`fusermount` installed.

None of this is gVisor-specific -- it's exactly what a FUSE daemon needs
on any Linux host; the point of the in-sandbox mode is that gVisor asks
for nothing extra on top.

## Example: `runsc` via Docker

```bash
docker run --runtime=runsc --rm -it \
  --device=/dev/fuse \
  --cap-add=SYS_ADMIN \
  -v /path/to/bare/repo.git:/repo.git:ro \
  gitfs:latest \
  gitfsd mount /repo.git /workspace
```

Then, from another shell into the same container (or a second process
started by your agent harness):

```bash
gitfsd status /workspace
gitfsd checkout /workspace <rev>
```

See the repo-root `deploy/Dockerfile` for a minimal image that builds
`gitfsd` and ships it with `git` (gitfsd shells out to `git cat-file`
and `git diff-tree`, so `git` itself must be on `PATH` in the image).

## Wiring it into an agent sandbox

A typical agent-sandbox flow:

1. On the host (or in a setup step before the sandbox starts), have a
   local or bare clone of the target repository -- a partial clone
   (`git clone --filter=blob:none <url>`) if you want gitfs's laziness
   to also mean "don't fetch blobs the agent never touches" rather than
   just "don't materialize files the agent never touches locally."
2. Bind-mount that repository's `GIT_DIR` read-only into the sandbox.
3. Start `gitfsd mount <repo> <workspace>` as (or under) the sandbox's
   PID 1 / init, foregrounded -- it serves the filesystem until it gets
   SIGINT/SIGTERM or a `gitfsd unmount` control request. Treat it the
   way you'd treat any other long-running sidecar process in the
   sandbox: something needs to supervise it (systemd, tini, your
   harness's own process manager) so a crash doesn't leave a stale
   mount.
4. Point the agent's working directory at `<workspace>`. Ordinary file
   reads/writes/`ls`/etc. all work through the FUSE mount with no agent
   changes needed.
5. Use `gitfsd status <workspace>` / `gitfsd checkout <workspace> <rev>`
   from your harness (not from inside the agent's own shell, unless you
   also expose the `gitfsd` binary there) for the fast-path
   status/checkout operations described in docs/architecture.md.
6. On teardown, `gitfsd unmount <workspace>` (or just let the sandbox
   container exit -- there's no host-side state to clean up beyond the
   per-mount state directory under `$GITFS_STATE_DIR`, which typically
   lives inside the ephemeral sandbox's own filesystem anyway).

## Security notes

- gitfsd itself never writes to the source repository's `GIT_DIR`; it
  only reads objects from it (`git cat-file`, `git diff-tree`,
  `git rev-parse`). Mounting the source repo read-only into the sandbox
  is safe and recommended.
- All local edits live in gitfsd's own overlay directory
  (`$GITFS_STATE_DIR/mounts/<hash>/overlay`), not in the source repo.
  An agent can write freely inside the mount without any risk to the
  repository the mount was created from.
- Because gVisor's sentry mediates every syscall the sandboxed gitfsd
  process makes (including the FUSE traffic itself), a compromised
  agent workload gains nothing by attacking gitfsd's `mount(2)` call
  path that it wouldn't already have from being inside the sandbox --
  it's still fully inside gVisor's syscall interception, same as every
  other process in the container.
- See docs/architecture.md's "Known limitations" section for what
  gitfs deliberately does not attempt (no `.git` exposed inside the
  mount, so `git` commands must run against the source repo directly,
  not against files reached through the FUSE mount).
