// Package ctl is the control protocol between the `gitfsd mount`
// long-running process and the short-lived `gitfsd status` / `gitfsd
// checkout` / `gitfsd unmount` CLI invocations that talk to it.
//
// gitfs's whole performance story depends on internal/status and
// internal/checkout running against the live, in-memory Root the mount
// daemon already holds (the overlay index, the store's blob/tree
// caches) -- a separate process re-reading everything from scratch
// would throw that away. So the daemon exposes a tiny newline-delimited
// JSON protocol over a Unix domain socket, and the CLI subcommands are
// just thin clients of it. This mirrors (at a fraction of the
// complexity) how `eden status`/`eden checkout` are thin clients of the
// long-running edenfs daemon over Thrift.
package ctl

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"time"

	"github.com/anuragag/tools/gitfs/internal/checkout"
	"github.com/anuragag/tools/gitfs/internal/status"
	"github.com/anuragag/tools/gitfs/internal/vfs"
)

// Request is one control-socket request. Exactly one connection is used
// per request/response pair.
type Request struct {
	Op  string `json:"op"` // "status", "checkout", "unmount", "ping"
	Rev string `json:"rev,omitempty"`
}

// Response is the reply to a Request.
type Response struct {
	OK       bool             `json:"ok"`
	Error    string           `json:"error,omitempty"`
	Status   []status.Change  `json:"status,omitempty"`
	Checkout *checkout.Result `json:"checkout,omitempty"`
}

// Handler holds what the server side needs to answer requests.
type Handler struct {
	Root *vfs.Root
	// Unmount is called (after the response is sent) to tear down the
	// mount in response to an "unmount" request. Required.
	Unmount func() error
}

// Serve accepts connections on ln until it errors (typically because the
// listener was closed by the caller during shutdown). Each connection
// carries exactly one request/response.
func Serve(ln net.Listener, h Handler) error {
	for {
		conn, err := ln.Accept()
		if err != nil {
			return err
		}
		go h.handle(conn)
	}
}

func (h Handler) handle(conn net.Conn) {
	defer conn.Close()
	var req Request
	if err := json.NewDecoder(bufio.NewReader(conn)).Decode(&req); err != nil {
		return
	}
	resp := h.dispatch(req)
	_ = json.NewEncoder(conn).Encode(resp)
}

func (h Handler) dispatch(req Request) Response {
	switch req.Op {
	case "ping":
		return Response{OK: true}
	case "status":
		return Response{OK: true, Status: status.Report(h.Root.Overlay)}
	case "checkout":
		res, err := checkout.Checkout(h.Root, req.Rev)
		if err != nil {
			return Response{Error: err.Error()}
		}
		return Response{OK: true, Checkout: res}
	case "unmount":
		if h.Unmount != nil {
			go func() { _ = h.Unmount() }()
		}
		return Response{OK: true}
	default:
		return Response{Error: fmt.Sprintf("unknown op %q", req.Op)}
	}
}

// call is the shared client-side request/response round trip.
func call(sockPath string, req Request) (*Response, error) {
	conn, err := net.DialTimeout("unix", sockPath, 5*time.Second)
	if err != nil {
		return nil, fmt.Errorf("gitfs/ctl: connecting to %s: %w", sockPath, err)
	}
	defer conn.Close()

	if err := json.NewEncoder(conn).Encode(req); err != nil {
		return nil, fmt.Errorf("gitfs/ctl: sending request: %w", err)
	}
	var resp Response
	if err := json.NewDecoder(bufio.NewReader(conn)).Decode(&resp); err != nil {
		return nil, fmt.Errorf("gitfs/ctl: reading response: %w", err)
	}
	if resp.Error != "" {
		return nil, errors.New(resp.Error)
	}
	return &resp, nil
}

// Ping checks that a gitfsd mount is alive and answering on sockPath.
func Ping(sockPath string) error {
	_, err := call(sockPath, Request{Op: "ping"})
	return err
}

// Status asks the running mount for its current dirty-path report.
func Status(sockPath string) ([]status.Change, error) {
	resp, err := call(sockPath, Request{Op: "status"})
	if err != nil {
		return nil, err
	}
	return resp.Status, nil
}

// Checkout asks the running mount to switch its base commit to rev.
func Checkout(sockPath, rev string) (*checkout.Result, error) {
	resp, err := call(sockPath, Request{Op: "checkout", Rev: rev})
	if err != nil {
		return nil, err
	}
	return resp.Checkout, nil
}

// Unmount asks the running mount to unmount and exit.
func Unmount(sockPath string) error {
	_, err := call(sockPath, Request{Op: "unmount"})
	return err
}
