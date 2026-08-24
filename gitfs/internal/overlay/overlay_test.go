package overlay

import (
	"os"
	"path/filepath"
	"testing"
)

func TestSetGetClear(t *testing.T) {
	dir := t.TempDir()
	o, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer o.Close()

	if _, ok := o.Get("a/b.txt"); ok {
		t.Fatal("expected no entry for clean path")
	}

	if err := o.Set("a/b.txt", Modified, false, "deadbeef"); err != nil {
		t.Fatal(err)
	}
	e, ok := o.Get("a/b.txt")
	if !ok || e.State != Modified || e.BaseOID != "deadbeef" {
		t.Fatalf("unexpected entry: %+v ok=%v", e, ok)
	}

	if err := o.Clear("a/b.txt"); err != nil {
		t.Fatal(err)
	}
	if _, ok := o.Get("a/b.txt"); ok {
		t.Fatal("expected entry to be gone after Clear")
	}
}

func TestListIsSortedAndOnlyDirty(t *testing.T) {
	dir := t.TempDir()
	o, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer o.Close()

	must(t, o.Set("z.txt", Added, false, ""))
	must(t, o.Set("a.txt", Modified, false, "abc"))
	must(t, o.Set("m/n.txt", Deleted, false, "def"))

	list := o.List()
	if len(list) != 3 {
		t.Fatalf("List() len = %d, want 3", len(list))
	}
	want := []string{"a.txt", "m/n.txt", "z.txt"}
	for i, e := range list {
		if e.Path != want[i] {
			t.Fatalf("List()[%d].Path = %s, want %s", i, e.Path, want[i])
		}
	}
}

func TestChildren(t *testing.T) {
	dir := t.TempDir()
	o, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer o.Close()

	must(t, o.Set("src/main.go", Modified, false, "abc"))
	must(t, o.Set("src/sub/new.go", Added, false, ""))
	must(t, o.Set("README.md", Added, false, ""))

	rootChildren := o.Children("")
	if len(rootChildren) != 1 || rootChildren[0].Path != "README.md" {
		t.Fatalf("root children = %+v", rootChildren)
	}

	srcChildren := o.Children("src")
	if len(srcChildren) != 1 || srcChildren[0].Path != "src/main.go" {
		t.Fatalf("src children = %+v", srcChildren)
	}

	if !o.HasDirtyDescendant("src") {
		t.Fatal("expected src to have a dirty descendant")
	}
	if o.HasDirtyDescendant("other") {
		t.Fatal("did not expect other to have a dirty descendant")
	}
}

func TestPersistenceAcrossReopen(t *testing.T) {
	dir := t.TempDir()
	o, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	must(t, o.Set("a.txt", Modified, false, "abc"))
	must(t, o.Set("b.txt", Added, false, ""))
	must(t, o.Clear("b.txt")) // added then reverted; should not persist
	must(t, o.Set("c.txt", Deleted, false, "xyz"))
	if err := o.Close(); err != nil {
		t.Fatal(err)
	}

	o2, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer o2.Close()

	list := o2.List()
	if len(list) != 2 {
		t.Fatalf("after reopen, List() = %+v, want 2 entries", list)
	}
	if _, ok := o2.Get("b.txt"); ok {
		t.Fatal("b.txt should not have persisted after Clear")
	}
	if e, ok := o2.Get("a.txt"); !ok || e.State != Modified {
		t.Fatalf("a.txt missing or wrong state after reopen: %+v", e)
	}
	if e, ok := o2.Get("c.txt"); !ok || e.State != Deleted {
		t.Fatalf("c.txt missing or wrong state after reopen: %+v", e)
	}
}

func TestCompact(t *testing.T) {
	dir := t.TempDir()
	o, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer o.Close()

	// Churn the same path many times to build up log history.
	for i := 0; i < 10; i++ {
		must(t, o.Set("a.txt", Modified, false, "abc"))
		must(t, o.Clear("a.txt"))
	}
	must(t, o.Set("a.txt", Modified, false, "abc"))

	logPath := filepath.Join(dir, "log")
	before, err := os.Stat(logPath)
	if err != nil {
		t.Fatal(err)
	}

	if err := o.Compact(); err != nil {
		t.Fatal(err)
	}

	after, err := os.Stat(logPath)
	if err != nil {
		t.Fatal(err)
	}
	if after.Size() >= before.Size() {
		t.Fatalf("expected compact to shrink log: before=%d after=%d", before.Size(), after.Size())
	}

	// State should be unaffected by compaction.
	if e, ok := o.Get("a.txt"); !ok || e.State != Modified {
		t.Fatalf("unexpected state after compact: %+v ok=%v", e, ok)
	}
	if o.DirtySinceCompact() != 0 {
		t.Fatalf("DirtySinceCompact() = %d, want 0", o.DirtySinceCompact())
	}
}

func TestFilePathAndEnsureParent(t *testing.T) {
	dir := t.TempDir()
	o, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer o.Close()

	if err := o.EnsureParent("a/b/c.txt"); err != nil {
		t.Fatal(err)
	}
	fp := o.FilePath("a/b/c.txt")
	if err := os.WriteFile(fp, []byte("hi"), 0o644); err != nil {
		t.Fatalf("could not write materialized file: %v", err)
	}
	if err := o.RemoveFile("a/b/c.txt"); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(fp); !os.IsNotExist(err) {
		t.Fatal("expected file to be removed")
	}
	// Removing again should be a no-op.
	if err := o.RemoveFile("a/b/c.txt"); err != nil {
		t.Fatal(err)
	}
}

func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}
