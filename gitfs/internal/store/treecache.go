package store

import (
	"container/list"
	"sync"
)

// treeCache caches parsed tree objects by OID. Trees are content-addressed
// and immutable, so there is never a staleness concern: the only question
// is how much memory to spend avoiding repeat `git cat-file` round trips
// when the FUSE layer looks up several siblings in the same directory in
// quick succession (which it does on every `ls`, `git status`, etc.).
type treeCache struct {
	maxEntries int // cap on total TreeEntry count held across all cached trees

	mu      sync.Mutex
	curSize int
	ll      *list.List
	items   map[OID]*list.Element
}

type treeCacheItem struct {
	oid     OID
	entries []TreeEntry
}

func newTreeCache(maxEntries int) *treeCache {
	return &treeCache{
		maxEntries: maxEntries,
		ll:         list.New(),
		items:      make(map[OID]*list.Element),
	}
}

func (c *treeCache) get(oid OID) ([]TreeEntry, bool) {
	if c.maxEntries <= 0 {
		return nil, false
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	el, ok := c.items[oid]
	if !ok {
		return nil, false
	}
	c.ll.MoveToFront(el)
	return el.Value.(*treeCacheItem).entries, true
}

func (c *treeCache) put(oid OID, entries []TreeEntry) {
	if c.maxEntries <= 0 || len(entries) > c.maxEntries {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()

	if el, ok := c.items[oid]; ok {
		c.ll.MoveToFront(el)
		return
	}

	el := c.ll.PushFront(&treeCacheItem{oid: oid, entries: entries})
	c.items[oid] = el
	c.curSize += len(entries)

	for c.curSize > c.maxEntries {
		back := c.ll.Back()
		if back == nil {
			break
		}
		item := back.Value.(*treeCacheItem)
		c.ll.Remove(back)
		delete(c.items, item.oid)
		c.curSize -= len(item.entries)
	}
}
