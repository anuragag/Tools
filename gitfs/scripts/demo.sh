#!/usr/bin/env bash
# Builds gitfsd and walks through mount -> edit -> status -> checkout ->
# unmount against a throwaway repo, printing each step. Useful as a
# smoke test after making changes, or as a copy-paste-able example of
# the CLI. Requires /dev/fuse and CAP_SYS_ADMIN (run as root, or inside
# a container with --device=/dev/fuse --cap-add=SYS_ADMIN).
set -euo pipefail

work="$(mktemp -d)"
trap 'gitfsd unmount "$work/mnt" 2>/dev/null || true; rm -rf "$work"' EXIT

echo "==> building gitfsd"
go build -o "$work/gitfsd" ./cmd/gitfsd
export PATH="$work:$PATH"

echo "==> creating a throwaway repo at $work/repo"
mkdir -p "$work/repo" "$work/mnt"
git -C "$work/repo" init -q --initial-branch=main
git -C "$work/repo" config user.email demo@example.com
git -C "$work/repo" config user.name demo
echo "hello gitfs" > "$work/repo/README.md"
mkdir -p "$work/repo/src"
echo "package main" > "$work/repo/src/main.go"
git -C "$work/repo" add .
git -C "$work/repo" commit -q -m first
first="$(git -C "$work/repo" rev-parse HEAD)"

echo "v2" >> "$work/repo/src/main.go"
git -C "$work/repo" add .
git -C "$work/repo" commit -q -m second
second="$(git -C "$work/repo" rev-parse HEAD)"

export GITFS_STATE_DIR="$work/state"

echo "==> mounting at $first"
gitfsd mount --ref="$first" "$work/repo" "$work/mnt" &
sleep 1

echo "==> ls mount"
ls -la "$work/mnt"

echo "==> reading src/main.go (served lazily, no local copy yet)"
cat "$work/mnt/src/main.go"

echo "==> editing README.md through the mount"
echo "local change" >> "$work/mnt/README.md"

echo "==> gitfsd status (O(dirty), not O(repo size))"
gitfsd status "$work/mnt"

echo "==> checking out $second (local edit should survive)"
gitfsd checkout "$work/mnt" "$second"
cat "$work/mnt/src/main.go"

echo "==> status after checkout"
gitfsd status "$work/mnt"

echo "==> unmounting"
gitfsd unmount "$work/mnt"
echo "done"
