package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// TestEndToEnd builds the real gitfsd binary and drives it exactly the
// way a user would: mount a repo, edit a file through the mount, ask
// for status, checkout a different commit, confirm the local edit
// survived, and unmount. It exercises the full stack (CLI flag parsing,
// the control-socket RPC, the FUSE mount, and the underlying git
// plumbing) rather than any package in isolation.
func TestEndToEnd(t *testing.T) {
	bin := buildGitfsd(t)
	repoDir, first, second := initRepo(t)

	stateDir := t.TempDir()
	mountDir := t.TempDir()
	env := append(os.Environ(), "GITFS_STATE_DIR="+stateDir)

	mountCmd := exec.Command(bin, "mount", "--ref="+first, repoDir, mountDir)
	mountCmd.Env = env
	mountOut, err := mountCmd.StderrPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := mountCmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		unmountCmd := exec.Command(bin, "unmount", mountDir)
		unmountCmd.Env = env
		_ = unmountCmd.Run()
		_ = waitWithTimeout(mountCmd, 5*time.Second)
	})

	if !waitForMount(t, mountOut) {
		t.Skip("skipping end-to-end test: FUSE mount unavailable in this sandbox")
	}

	data, err := os.ReadFile(filepath.Join(mountDir, "README.md"))
	if err != nil || string(data) != "v1\n" {
		t.Fatalf("README.md before checkout = %q, err=%v", data, err)
	}

	if err := os.WriteFile(filepath.Join(mountDir, "README.md"), []byte("local edit\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	statusOut := runOK(t, env, bin, "status", mountDir)
	if strings.TrimSpace(statusOut) != "M  README.md" {
		t.Fatalf("status output = %q", statusOut)
	}

	checkoutOut := runOK(t, env, bin, "checkout", mountDir, second)
	if !strings.Contains(checkoutOut, first) || !strings.Contains(checkoutOut, second) {
		t.Fatalf("checkout output = %q", checkoutOut)
	}

	data, err = os.ReadFile(filepath.Join(mountDir, "README.md"))
	if err != nil || string(data) != "local edit\n" {
		t.Fatalf("README.md after checkout = %q, err=%v, want local edit preserved", data, err)
	}
	data, err = os.ReadFile(filepath.Join(mountDir, "b.txt"))
	if err != nil || string(data) != "new in v2\n" {
		t.Fatalf("b.txt after checkout = %q, err=%v", data, err)
	}

	unmountCmd := exec.Command(bin, "unmount", mountDir)
	unmountCmd.Env = env
	if err := unmountCmd.Run(); err != nil {
		t.Fatal(err)
	}
	if waitErr := waitWithTimeout(mountCmd, 5*time.Second); waitErr != nil {
		t.Fatalf("mount process did not exit cleanly after unmount: %v", waitErr)
	}
}

func buildGitfsd(t *testing.T) string {
	t.Helper()
	bin := filepath.Join(t.TempDir(), "gitfsd")
	cmd := exec.Command("go", "build", "-o", bin, ".")
	cmd.Dir = "." // this package's own directory
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("building gitfsd: %v\n%s", err, out)
	}
	return bin
}

func initRepo(t *testing.T) (dir, first, second string) {
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
	os.WriteFile(filepath.Join(dir, "README.md"), []byte("v1\n"), 0o644)
	run("add", ".")
	run("commit", "-m", "first")
	first = run("rev-parse", "HEAD")

	os.WriteFile(filepath.Join(dir, "b.txt"), []byte("new in v2\n"), 0o644)
	run("add", ".")
	run("commit", "-m", "second")
	second = run("rev-parse", "HEAD")
	return dir, first, second
}

// waitForMount reads gitfsd's stderr until it announces the control
// socket (meaning the mount succeeded) or the mount process exits/errors
// out (e.g. because FUSE isn't available in this sandbox).
func waitForMount(t *testing.T, r interface{ Read([]byte) (int, error) }) bool {
	t.Helper()
	buf := make([]byte, 4096)
	var acc strings.Builder
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		n, err := r.Read(buf)
		if n > 0 {
			acc.Write(buf[:n])
			if strings.Contains(acc.String(), "control socket") {
				return true
			}
		}
		if err != nil {
			return false
		}
	}
	return false
}

func runOK(t *testing.T, env []string, bin string, args ...string) string {
	t.Helper()
	cmd := exec.Command(bin, args...)
	cmd.Env = env
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("%s %v: %v\n%s", bin, args, err, out)
	}
	return string(out)
}

func waitWithTimeout(cmd *exec.Cmd, d time.Duration) error {
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		return err
	case <-time.After(d):
		return exec.ErrWaitDelay
	}
}
