package store

import (
	"bufio"
	"fmt"
	"io"
	"os/exec"
	"strconv"
	"strings"
	"sync"
)

// Stat is the header-only result of a `cat-file --batch-check` query:
// object type and size without transferring (or caching) any content.
// vfs uses this for Getattr so that `ls -la` and friends don't force a
// full blob fetch for every file in a directory.
type Stat struct {
	OID  OID
	Type ObjectType
	Size int64
}

// checkProc is the --batch-check analogue of batchProc: same
// request/response shape, but the response has no body to read.
type checkProc struct {
	cmd    *exec.Cmd
	stdin  io.WriteCloser
	stdout *bufio.Reader
	mu     sync.Mutex
}

func startCheckProc(gitDir string) (*checkProc, error) {
	cmd := exec.Command("git", "--git-dir", gitDir, "cat-file", "--batch-check")
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("gitfs/store: starting git cat-file --batch-check: %w", err)
	}
	return &checkProc{cmd: cmd, stdin: stdin, stdout: bufio.NewReaderSize(stdout, 4096)}, nil
}

func (c *checkProc) stat(rev string) (Stat, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if _, err := io.WriteString(c.stdin, rev+"\n"); err != nil {
		return Stat{}, fmt.Errorf("gitfs/store: writing to git cat-file --batch-check: %w", err)
	}
	line, err := c.stdout.ReadString('\n')
	if err != nil {
		return Stat{}, fmt.Errorf("gitfs/store: reading git cat-file --batch-check: %w", err)
	}
	line = strings.TrimSuffix(line, "\n")
	fields := strings.Fields(line)
	if len(fields) == 2 && fields[1] == "missing" {
		return Stat{}, &ErrNotFound{Ref: rev}
	}
	if len(fields) != 3 {
		return Stat{}, fmt.Errorf("gitfs/store: unexpected git cat-file --batch-check line %q", line)
	}
	size, err := strconv.ParseInt(fields[2], 10, 64)
	if err != nil {
		return Stat{}, fmt.Errorf("gitfs/store: bad size in %q: %w", line, err)
	}
	return Stat{OID: OID(fields[0]), Type: ObjectType(fields[1]), Size: size}, nil
}

func (c *checkProc) close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	_ = c.stdin.Close()
	return c.cmd.Wait()
}

type checkPool struct {
	procs chan *checkProc
}

func newCheckPool(gitDir string, size int) (*checkPool, error) {
	if size < 1 {
		size = 1
	}
	procs := make(chan *checkProc, size)
	for i := 0; i < size; i++ {
		p, err := startCheckProc(gitDir)
		if err != nil {
			close(procs)
			for existing := range procs {
				_ = existing.close()
			}
			return nil, err
		}
		procs <- p
	}
	return &checkPool{procs: procs}, nil
}

func (p *checkPool) stat(rev string) (Stat, error) {
	proc := <-p.procs
	defer func() { p.procs <- proc }()
	return proc.stat(rev)
}

func (p *checkPool) close() error {
	close(p.procs)
	var firstErr error
	for proc := range p.procs {
		if err := proc.close(); err != nil && firstErr == nil {
			firstErr = err
		}
	}
	return firstErr
}
