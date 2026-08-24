// Command gitfsd mounts a git repository as a lazy, git-native
// filesystem and drives status/checkout against a running mount. See
// docs/architecture.md for the design and docs/gvisor.md for running it
// inside a gVisor sandbox.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"strings"
	"syscall"

	"github.com/anuragag/tools/gitfs/internal/ctl"
	"github.com/anuragag/tools/gitfs/internal/statedir"
	"github.com/anuragag/tools/gitfs/internal/vfs"
)

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}

	var err error
	switch os.Args[1] {
	case "mount":
		err = cmdMount(os.Args[2:])
	case "status":
		err = cmdStatus(os.Args[2:])
	case "checkout":
		err = cmdCheckout(os.Args[2:])
	case "unmount":
		err = cmdUnmount(os.Args[2:])
	case "-h", "--help", "help":
		usage()
		return
	default:
		usage()
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "gitfsd:", err)
		os.Exit(1)
	}
}

func usage() {
	fmt.Fprint(os.Stderr, `gitfsd - a git-native lazy filesystem

Usage:
  gitfsd mount [flags] <repo> <mountpoint>
  gitfsd status <mountpoint>
  gitfsd checkout <mountpoint> <rev>
  gitfsd unmount <mountpoint>

"mount" runs in the foreground and serves the filesystem until it
receives SIGINT/SIGTERM or an "unmount" request. Run it under your
process supervisor of choice (systemd, tini, the container's own PID 1)
inside a sandbox; see docs/gvisor.md.
`)
}

// resolveGitDir accepts either a working-tree path or a bare/mirror
// repository path and returns the canonical GIT_DIR git itself would
// use, so users don't have to know or spell out ".git".
func resolveGitDir(repoPath string) (string, error) {
	cmd := exec.Command("git", "-C", repoPath, "rev-parse", "--absolute-git-dir")
	out, err := cmd.Output()
	if err != nil {
		return "", fmt.Errorf("resolving git dir for %s: %w", repoPath, err)
	}
	return strings.TrimSpace(string(out)), nil
}

func cmdMount(args []string) error {
	fs := flag.NewFlagSet("mount", flag.ExitOnError)
	rev := fs.String("ref", "HEAD", "git revision to check out")
	overlayDir := fs.String("overlay", "", "overlay directory (default: derived per-mount state dir)")
	allowOther := fs.Bool("allow-other", true, "allow other users/processes to access the mount (needed for most container setups)")
	debug := fs.Bool("debug", false, "log every FUSE operation")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 2 {
		return fmt.Errorf("mount: expected <repo> <mountpoint>, got %d args", fs.NArg())
	}
	repoPath, mountpoint := fs.Arg(0), fs.Arg(1)

	gitDir, err := resolveGitDir(repoPath)
	if err != nil {
		return err
	}

	state, err := statedir.For(mountpoint)
	if err != nil {
		return err
	}
	ovlDir := *overlayDir
	if ovlDir == "" {
		ovlDir = statedir.OverlayDir(state)
	}
	sockPath := statedir.SocketPath(state)

	if err := os.MkdirAll(mountpoint, 0o755); err != nil {
		return fmt.Errorf("creating mountpoint %s: %w", mountpoint, err)
	}

	server, root, st, err := vfs.Mount(mountpoint, gitDir, ovlDir, vfs.MountOptions{
		Rev:        *rev,
		AllowOther: *allowOther,
		Debug:      *debug,
	})
	if err != nil {
		return fmt.Errorf("mounting: %w", err)
	}
	fmt.Fprintf(os.Stderr, "gitfsd: mounted %s at %s (ref=%s)\n", repoPath, mountpoint, *rev)

	_ = os.Remove(sockPath)
	ln, err := net.Listen("unix", sockPath)
	if err != nil {
		_ = server.Unmount()
		_ = st.Close()
		return fmt.Errorf("listening on control socket %s: %w", sockPath, err)
	}
	fmt.Fprintf(os.Stderr, "gitfsd: control socket %s\n", sockPath)

	unmountOnce := make(chan struct{})
	doUnmount := func() error {
		select {
		case <-unmountOnce:
			return nil
		default:
			close(unmountOnce)
		}
		_ = ln.Close()
		_ = os.Remove(sockPath)
		err := server.Unmount()
		_ = st.Close()
		return err
	}

	go func() {
		_ = ctl.Serve(ln, ctl.Handler{Root: root, Unmount: doUnmount})
	}()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigCh
		_ = doUnmount()
	}()

	server.Wait()
	_ = doUnmount()
	return nil
}

func cmdStatus(args []string) error {
	fs := flag.NewFlagSet("status", flag.ExitOnError)
	short := fs.Bool("short", true, "print `git status --short`-style output")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 1 {
		return fmt.Errorf("status: expected <mountpoint>, got %d args", fs.NArg())
	}
	sockPath, err := socketFor(fs.Arg(0))
	if err != nil {
		return err
	}
	changes, err := ctl.Status(sockPath)
	if err != nil {
		return err
	}
	if *short {
		for _, c := range changes {
			fmt.Printf("%c  %s\n", c.Kind, c.Path)
		}
		return nil
	}
	b, err := json.MarshalIndent(changes, "", "  ")
	if err != nil {
		return err
	}
	fmt.Println(string(b))
	return nil
}

func cmdCheckout(args []string) error {
	fs := flag.NewFlagSet("checkout", flag.ExitOnError)
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 2 {
		return fmt.Errorf("checkout: expected <mountpoint> <rev>, got %d args", fs.NArg())
	}
	sockPath, err := socketFor(fs.Arg(0))
	if err != nil {
		return err
	}
	res, err := ctl.Checkout(sockPath, fs.Arg(1))
	if err != nil {
		return err
	}
	fmt.Printf("checked out %s -> %s (%d changed path(s), %d kept as local edits)\n",
		res.FromCommit, res.ToCommit, len(res.ChangedPaths), len(res.KeptLocalEdits))
	return nil
}

func cmdUnmount(args []string) error {
	fs := flag.NewFlagSet("unmount", flag.ExitOnError)
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 1 {
		return fmt.Errorf("unmount: expected <mountpoint>, got %d args", fs.NArg())
	}
	sockPath, err := socketFor(fs.Arg(0))
	if err != nil {
		return err
	}
	return ctl.Unmount(sockPath)
}

func socketFor(mountpoint string) (string, error) {
	state, err := statedir.For(mountpoint)
	if err != nil {
		return "", err
	}
	return statedir.SocketPath(state), nil
}
