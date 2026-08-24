package vfs

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// initTestRepo creates a throwaway git repo with a couple of commits.
func initTestRepo(t *testing.T) (dir string) {
	t.Helper()
	dir = t.TempDir()

	run := func(args ...string) {
		t.Helper()
		cmd := exec.Command("git", args...)
		cmd.Dir = dir
		cmd.Env = append(os.Environ(),
			"GIT_AUTHOR_NAME=gitfs-test", "GIT_AUTHOR_EMAIL=gitfs-test@example.com",
			"GIT_COMMITTER_NAME=gitfs-test", "GIT_COMMITTER_EMAIL=gitfs-test@example.com",
		)
		out, err := cmd.CombinedOutput()
		if err != nil {
			t.Fatalf("git %v: %v\n%s", args, err, out)
		}
	}

	run("init", "--initial-branch=main", ".")
	if err := os.WriteFile(filepath.Join(dir, "README.md"), []byte("hello gitfs\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(dir, "src"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "src", "main.go"), []byte("package main\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	run("add", ".")
	run("commit", "-m", "first")
	return dir
}

// mountForTest mounts a fresh gitfs FUSE filesystem over a temp repo and
// returns the mountpoint and root, tearing everything down on cleanup.
// If the sandbox refuses the mount syscall (common in restricted CI),
// the test is skipped rather than failed.
func mountForTest(t *testing.T) (mountpoint string, root *Root) {
	t.Helper()
	repoDir := initTestRepo(t)
	overlayDir := t.TempDir()
	mountDir := t.TempDir()

	server, r, st, err := Mount(mountDir, filepath.Join(repoDir, ".git"), overlayDir, MountOptions{})
	if err != nil {
		if isPermissionish(err) {
			t.Skipf("skipping FUSE mount test: %v", err)
		}
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = server.Unmount()
		_ = st.Close()
	})
	// vfs.Mount (via fs.Mount) already starts serving and waits for the
	// mount to be ready before returning.
	return mountDir, r
}

func isPermissionish(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, os.ErrPermission) {
		return true
	}
	msg := strings.ToLower(err.Error())
	for _, s := range []string{"permission denied", "operation not permitted", "no such device", "not permitted"} {
		if strings.Contains(msg, s) {
			return true
		}
	}
	return false
}

func TestMountReadBaseFiles(t *testing.T) {
	mountDir, _ := mountForTest(t)

	data, err := os.ReadFile(filepath.Join(mountDir, "README.md"))
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "hello gitfs\n" {
		t.Fatalf("README.md content = %q", data)
	}

	data, err = os.ReadFile(filepath.Join(mountDir, "src", "main.go"))
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "package main\n" {
		t.Fatalf("src/main.go content = %q", data)
	}

	entries, err := os.ReadDir(mountDir)
	if err != nil {
		t.Fatal(err)
	}
	names := map[string]bool{}
	for _, e := range entries {
		names[e.Name()] = true
	}
	if !names["README.md"] || !names["src"] {
		t.Fatalf("unexpected root listing: %v", names)
	}
}

func TestMountWriteCreatesOverlayEntry(t *testing.T) {
	mountDir, root := mountForTest(t)

	if err := os.WriteFile(filepath.Join(mountDir, "README.md"), []byte("modified content\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(mountDir, "README.md"))
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "modified content\n" {
		t.Fatalf("after write, content = %q", data)
	}

	if err := os.WriteFile(filepath.Join(mountDir, "NEW.txt"), []byte("brand new\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	entries := root.Overlay.List()
	byPath := map[string]bool{}
	for _, e := range entries {
		byPath[e.Path] = true
	}
	if !byPath["README.md"] {
		t.Fatalf("expected README.md dirty, got %v", entries)
	}
	if !byPath["NEW.txt"] {
		t.Fatalf("expected NEW.txt dirty, got %v", entries)
	}

	// Reading src/main.go, which was never touched, must not have
	// created any overlay entry -- that's the whole point of lazy
	// serving.
	if _, err := os.ReadFile(filepath.Join(mountDir, "src", "main.go")); err != nil {
		t.Fatal(err)
	}
	for _, e := range root.Overlay.List() {
		if e.Path == "src/main.go" {
			t.Fatalf("reading src/main.go should not have dirtied it")
		}
	}
}

func TestMountUnlinkAndMkdir(t *testing.T) {
	mountDir, root := mountForTest(t)

	if err := os.Remove(filepath.Join(mountDir, "README.md")); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(mountDir, "README.md")); !os.IsNotExist(err) {
		t.Fatalf("expected README.md to be gone, got err=%v", err)
	}
	e, ok := root.Overlay.Get("README.md")
	if !ok || e.State != "deleted" {
		t.Fatalf("expected deleted tombstone, got %+v ok=%v", e, ok)
	}

	if err := os.Mkdir(filepath.Join(mountDir, "newdir"), 0o755); err != nil {
		t.Fatal(err)
	}
	fi, err := os.Stat(filepath.Join(mountDir, "newdir"))
	if err != nil || !fi.IsDir() {
		t.Fatalf("newdir not a directory: %v %v", fi, err)
	}
}

func TestMountRename(t *testing.T) {
	mountDir, _ := mountForTest(t)

	if err := os.Rename(filepath.Join(mountDir, "README.md"), filepath.Join(mountDir, "RENAMED.md")); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(mountDir, "README.md")); !os.IsNotExist(err) {
		t.Fatalf("expected old path gone: %v", err)
	}
	data, err := os.ReadFile(filepath.Join(mountDir, "RENAMED.md"))
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "hello gitfs\n" {
		t.Fatalf("renamed content = %q", data)
	}
}
