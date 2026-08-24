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

// object is a fully-read response from `git cat-file --batch`.
type object struct {
	OID     OID
	Type    ObjectType
	Content []byte
}

// batchProc wraps one long-lived `git cat-file --batch` subprocess. The
// protocol is strictly request/response over a pair of pipes, so a single
// batchProc can only serve one caller at a time; batchPool below manages a
// small set of them to give the FUSE layer real concurrency.
type batchProc struct {
	cmd    *exec.Cmd
	stdin  io.WriteCloser
	stdout *bufio.Reader
	mu     sync.Mutex
}

func startBatchProc(gitDir string) (*batchProc, error) {
	cmd := exec.Command("git", "--git-dir", gitDir, "cat-file", "--batch")
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("gitfs/store: starting git cat-file --batch: %w", err)
	}
	return &batchProc{cmd: cmd, stdin: stdin, stdout: bufio.NewReaderSize(stdout, 64*1024)}, nil
}

// get resolves and fetches one object. rev may be any git revision
// expression git-rev-parse understands: a full OID, a ref name, or an
// extended form like "HEAD^{tree}" or "<oid>:<path>".
func (b *batchProc) get(rev string) (*object, error) {
	b.mu.Lock()
	defer b.mu.Unlock()

	if _, err := io.WriteString(b.stdin, rev+"\n"); err != nil {
		return nil, fmt.Errorf("gitfs/store: writing to git cat-file --batch: %w", err)
	}

	header, err := b.stdout.ReadString('\n')
	if err != nil {
		return nil, fmt.Errorf("gitfs/store: reading git cat-file --batch header: %w", err)
	}
	header = strings.TrimSuffix(header, "\n")

	fields := strings.Fields(header)
	if len(fields) == 2 && fields[1] == "missing" {
		return nil, &ErrNotFound{Ref: rev}
	}
	if len(fields) != 3 {
		return nil, fmt.Errorf("gitfs/store: unexpected git cat-file --batch header %q", header)
	}
	size, err := strconv.ParseInt(fields[2], 10, 64)
	if err != nil {
		return nil, fmt.Errorf("gitfs/store: bad size in header %q: %w", header, err)
	}

	content := make([]byte, size)
	if _, err := io.ReadFull(b.stdout, content); err != nil {
		return nil, fmt.Errorf("gitfs/store: reading object body for %s: %w", rev, err)
	}
	// cat-file --batch always terminates the object with a trailing '\n'.
	if _, err := b.stdout.Discard(1); err != nil {
		return nil, fmt.Errorf("gitfs/store: reading object trailer for %s: %w", rev, err)
	}

	return &object{OID: OID(fields[0]), Type: ObjectType(fields[1]), Content: content}, nil
}

func (b *batchProc) close() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	_ = b.stdin.Close()
	return b.cmd.Wait()
}

// batchPool round-robins requests across a fixed set of batchProcs so that
// concurrent FUSE lookups don't serialize behind a single subprocess pipe.
type batchPool struct {
	procs chan *batchProc
}

func newBatchPool(gitDir string, size int) (*batchPool, error) {
	if size < 1 {
		size = 1
	}
	procs := make(chan *batchProc, size)
	for i := 0; i < size; i++ {
		p, err := startBatchProc(gitDir)
		if err != nil {
			// Drain and close whatever we already started.
			close(procs)
			for existing := range procs {
				_ = existing.close()
			}
			return nil, err
		}
		procs <- p
	}
	return &batchPool{procs: procs}, nil
}

func (p *batchPool) get(rev string) (*object, error) {
	proc := <-p.procs
	defer func() { p.procs <- proc }()
	return proc.get(rev)
}

func (p *batchPool) close() error {
	close(p.procs)
	var firstErr error
	for proc := range p.procs {
		if err := proc.close(); err != nil && firstErr == nil {
			firstErr = err
		}
	}
	return firstErr
}
