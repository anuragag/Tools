// Package store gives gitfs read access to a git repository's object
// database. Rather than re-implementing git's pack/loose object format,
// delta resolution, and (critically) the partial-clone "promisor" fetch
// path, it drives long-lived `git cat-file --batch` subprocesses and lets
// git itself do that work. This is the piece of gitfs's laziness story
// that EdenFS had to build from scratch (a custom backing-store protocol
// talking to Mercurial's remotefilelog server): git already ships an
// equivalent in the form of partial clones, so gitfs only has to be a
// thin, fast client of `git cat-file`.
package store

import "fmt"

// OID is a hex-encoded git object id. It is not necessarily 40 characters:
// repositories using the sha256 object format have 64-character OIDs, and
// gitfs treats the hash algorithm as an opaque string length rather than
// assuming sha1.
type OID string

// ZeroOID reports whether an OID is the unset/empty value.
func (o OID) IsZero() bool { return o == "" }

func (o OID) String() string { return string(o) }

// ObjectType is one of the four git object types.
type ObjectType string

const (
	TypeBlob   ObjectType = "blob"
	TypeTree   ObjectType = "tree"
	TypeCommit ObjectType = "commit"
	TypeTag    ObjectType = "tag"
)

// ErrNotFound is returned when git reports an object id or revision as
// missing (the "<oid> missing" batch response line).
type ErrNotFound struct {
	Ref string
}

func (e *ErrNotFound) Error() string { return fmt.Sprintf("gitfs/store: not found: %s", e.Ref) }

// ErrWrongType is returned when the caller asked for a specific object
// type (e.g. Tree) but the object resolved to something else.
type ErrWrongType struct {
	Ref  string
	Want ObjectType
	Got  ObjectType
}

func (e *ErrWrongType) Error() string {
	return fmt.Sprintf("gitfs/store: %s: expected %s, got %s", e.Ref, e.Want, e.Got)
}
