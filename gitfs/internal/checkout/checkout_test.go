package checkout

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"github.com/anuragag/tools/gitfs/internal/vfs"
)

func initTwoCommitRepo(t *testing.T) (dir string, first, second string) {
	t.Helper()
	dir = t.TempDir()

	run := func(args ...string) string {
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
		return strings.TrimSpace(string(out))
	}

	run("init", "--initial-branch=main", ".")
	os.WriteFile(filepath.Join(dir, "a.txt"), []byte("v1\n"), 0o644)
	os.WriteFile(filepath.Join(dir, "unchanged.txt"), []byte("same\n"), 0o644)
	run("add", ".")
	run("commit", "-m", "first")
	first = run("rev-parse", "HEAD")

	os.WriteFile(filepath.Join(dir, "a.txt"), []byte("v2\n"), 0o644)
	os.WriteFile(filepath.Join(dir, "b.txt"), []byte("new in v2\n"), 0o644)
	run("add", ".")
	run("commit", "-m", "second")
	second = run("rev-parse", "HEAD")

	return dir, first, second
}

func mountForTest(t *testing.T, repoDir, rev string) (mountDir string, root *vfs.Root) {
	t.Helper()
	overlayDir := t.TempDir()
	mountDir = t.TempDir()

	server, r, st, err := vfs.Mount(mountDir, filepath.Join(repoDir, ".git"), overlayDir, vfs.MountOptions{Rev: rev})
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

func TestCheckoutSwitchesUnmodifiedFiles(t *testing.T) {
	repoDir, first, second := initTwoCommitRepo(t)
	mountDir, root := mountForTest(t, repoDir, first)

	data, err := os.ReadFile(filepath.Join(mountDir, "a.txt"))
	if err != nil || string(data) != "v1\n" {
		t.Fatalf("a.txt before checkout = %q, err=%v", data, err)
	}

	res, err := Checkout(root, second)
	if err != nil {
		t.Fatal(err)
	}
	if string(res.FromCommit) != first || string(res.ToCommit) != second {
		t.Fatalf("unexpected result commits: %+v", res)
	}
	wantChanged := map[string]bool{"a.txt": true, "b.txt": true}
	for _, p := range res.ChangedPaths {
		if !wantChanged[p] {
			t.Errorf("unexpected changed path %q", p)
		}
		delete(wantChanged, p)
	}
	if len(wantChanged) != 0 {
		t.Fatalf("missing changed paths: %v", wantChanged)
	}

	data, err = os.ReadFile(filepath.Join(mountDir, "a.txt"))
	if err != nil || string(data) != "v2\n" {
		t.Fatalf("a.txt after checkout = %q, err=%v", data, err)
	}
	data, err = os.ReadFile(filepath.Join(mountDir, "b.txt"))
	if err != nil || string(data) != "new in v2\n" {
		t.Fatalf("b.txt after checkout = %q, err=%v", data, err)
	}
	data, err = os.ReadFile(filepath.Join(mountDir, "unchanged.txt"))
	if err != nil || string(data) != "same\n" {
		t.Fatalf("unchanged.txt after checkout = %q, err=%v", data, err)
	}
}

func TestCheckoutKeepsLocalEdits(t *testing.T) {
	repoDir, first, second := initTwoCommitRepo(t)
	mountDir, root := mountForTest(t, repoDir, first)

	// Locally modify a.txt, which also changes between first and second.
	if err := os.WriteFile(filepath.Join(mountDir, "a.txt"), []byte("local edit\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	res, err := Checkout(root, second)
	if err != nil {
		t.Fatal(err)
	}
	if len(res.KeptLocalEdits) != 1 || res.KeptLocalEdits[0] != "a.txt" {
		t.Fatalf("KeptLocalEdits = %v, want [a.txt]", res.KeptLocalEdits)
	}

	data, err := os.ReadFile(filepath.Join(mountDir, "a.txt"))
	if err != nil || string(data) != "local edit\n" {
		t.Fatalf("a.txt after checkout = %q, err=%v, want local edit preserved", data, err)
	}
}
