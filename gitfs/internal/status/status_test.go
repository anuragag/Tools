package status

import (
	"testing"

	"github.com/anuragag/tools/gitfs/internal/overlay"
)

func TestReportAndSummary(t *testing.T) {
	dir := t.TempDir()
	ovl, err := overlay.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer ovl.Close()

	if err := ovl.Set("a.txt", overlay.Modified, false, "abc"); err != nil {
		t.Fatal(err)
	}
	if err := ovl.Set("b.txt", overlay.Added, false, ""); err != nil {
		t.Fatal(err)
	}
	if err := ovl.Set("c.txt", overlay.Deleted, false, "def"); err != nil {
		t.Fatal(err)
	}

	changes := Report(ovl)
	if len(changes) != 3 {
		t.Fatalf("Report() = %+v, want 3 entries", changes)
	}
	want := map[string]Kind{"a.txt": Modified, "b.txt": Added, "c.txt": Deleted}
	for _, c := range changes {
		if want[c.Path] != c.Kind {
			t.Errorf("Report()[%s] = %c, want %c", c.Path, c.Kind, want[c.Path])
		}
	}

	if got := Summary(changes); got != "1 added, 1 modified, 1 deleted" {
		t.Fatalf("Summary() = %q", got)
	}

	short := FormatShort(changes)
	if short != "M  a.txt\nA  b.txt\nD  c.txt\n" {
		t.Fatalf("FormatShort() = %q", short)
	}
}

func TestReportEmpty(t *testing.T) {
	dir := t.TempDir()
	ovl, err := overlay.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer ovl.Close()

	if changes := Report(ovl); len(changes) != 0 {
		t.Fatalf("Report() on clean overlay = %+v", changes)
	}
	if got := Summary(nil); got != "clean" {
		t.Fatalf("Summary(nil) = %q", got)
	}
}
