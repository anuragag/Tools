package store

import (
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"testing"
)

// initTestRepo creates a throwaway git repo with a couple of commits and
// returns its GIT_DIR plus the OIDs of the two commits (first, second).
func initTestRepo(t *testing.T) (gitDir string, first, second OID) {
	t.Helper()
	dir := t.TempDir()

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
		return string(out)
	}

	run("init", "--initial-branch=main", ".")
	run("config", "user.name", "gitfs-test")
	run("config", "user.email", "gitfs-test@example.com")

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
	firstOut := run("rev-parse", "HEAD")
	first = OID(trimNL(firstOut))

	if err := os.WriteFile(filepath.Join(dir, "src", "main.go"), []byte("package main\n\nfunc main() {}\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "src", "util.go"), []byte("package main\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	run("add", ".")
	run("commit", "-m", "second")
	secondOut := run("rev-parse", "HEAD")
	second = OID(trimNL(secondOut))

	return filepath.Join(dir, ".git"), first, second
}

func trimNL(s string) string {
	for len(s) > 0 && (s[len(s)-1] == '\n' || s[len(s)-1] == '\r') {
		s = s[:len(s)-1]
	}
	return s
}

func TestResolveCommitAndTree(t *testing.T) {
	gitDir, first, second := initTestRepo(t)
	s, err := Open(gitDir, Options{})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	c, err := s.ResolveCommit("main")
	if err != nil {
		t.Fatal(err)
	}
	if c.OID != second {
		t.Fatalf("ResolveCommit(main) = %s, want %s", c.OID, second)
	}
	if len(c.Parents) != 1 || c.Parents[0] != first {
		t.Fatalf("unexpected parents: %v", c.Parents)
	}

	entries, err := s.Tree(c.Tree)
	if err != nil {
		t.Fatal(err)
	}
	names := entryNames(entries)
	sort.Strings(names)
	want := []string{"README.md", "src"}
	if !equalStrings(names, want) {
		t.Fatalf("root tree entries = %v, want %v", names, want)
	}

	var srcOID OID
	for _, e := range entries {
		if e.Name == "src" {
			if e.Type != TypeTree {
				t.Fatalf("src entry type = %s, want tree", e.Type)
			}
			srcOID = e.OID
		}
	}
	srcEntries, err := s.Tree(srcOID)
	if err != nil {
		t.Fatal(err)
	}
	srcNames := entryNames(srcEntries)
	sort.Strings(srcNames)
	if !equalStrings(srcNames, []string{"main.go", "util.go"}) {
		t.Fatalf("src tree entries = %v", srcNames)
	}
}

func TestBlob(t *testing.T) {
	gitDir, _, second := initTestRepo(t)
	s, err := Open(gitDir, Options{})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	c, err := s.ResolveCommit(string(second))
	if err != nil {
		t.Fatal(err)
	}
	entries, err := s.Tree(c.Tree)
	if err != nil {
		t.Fatal(err)
	}
	var readmeOID OID
	for _, e := range entries {
		if e.Name == "README.md" {
			readmeOID = e.OID
		}
	}
	data, err := s.Blob(readmeOID)
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "hello gitfs\n" {
		t.Fatalf("blob content = %q", data)
	}

	// Second fetch should hit the cache and return identical content.
	data2, err := s.Blob(readmeOID)
	if err != nil {
		t.Fatal(err)
	}
	if string(data2) != string(data) {
		t.Fatalf("cached blob mismatch")
	}
}

func TestDiffTree(t *testing.T) {
	gitDir, first, second := initTestRepo(t)
	s, err := Open(gitDir, Options{})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	paths, err := s.DiffTree(first, second)
	if err != nil {
		t.Fatal(err)
	}
	sort.Strings(paths)
	want := []string{"src/main.go", "src/util.go"}
	if !equalStrings(paths, want) {
		t.Fatalf("DiffTree = %v, want %v", paths, want)
	}
}

func TestMissingObject(t *testing.T) {
	gitDir, _, _ := initTestRepo(t)
	s, err := Open(gitDir, Options{})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	_, err = s.Tree(OID("0000000000000000000000000000000000000000"))
	if err == nil {
		t.Fatal("expected error for missing object")
	}
	var nf *ErrNotFound
	if !isNotFound(err, &nf) {
		t.Fatalf("expected ErrNotFound, got %T: %v", err, err)
	}
}

func isNotFound(err error, target **ErrNotFound) bool {
	nf, ok := err.(*ErrNotFound)
	if ok {
		*target = nf
	}
	return ok
}

func entryNames(entries []TreeEntry) []string {
	names := make([]string, len(entries))
	for i, e := range entries {
		names[i] = e.Name
	}
	return names
}

func equalStrings(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}
