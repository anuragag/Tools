package store

import (
	"container/list"
	"sync"
)

// blobCache is a simple size-bounded LRU cache for decoded blob content.
// FUSE read() calls arrive in page-sized chunks (typically 128 KiB), and
// without a cache each of those would round-trip through `git cat-file`
// to re-fetch the same blob. Since git has no sub-blob fetch granularity
// (see Store's doc comment), the cheapest correct thing to cache is the
// whole decoded object.
type blobCache struct {
	maxBytes int64

	mu       sync.Mutex
	curBytes int64
	ll       *list.List // front = most recently used
	items    map[OID]*list.Element
}

type cacheEntry struct {
	oid  OID
	data []byte
}

func newBlobCache(maxBytes int64) *blobCache {
	return &blobCache{
		maxBytes: maxBytes,
		ll:       list.New(),
		items:    make(map[OID]*list.Element),
	}
}

func (c *blobCache) get(oid OID) ([]byte, bool) {
	if c.maxBytes <= 0 {
		return nil, false
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	el, ok := c.items[oid]
	if !ok {
		return nil, false
	}
	c.ll.MoveToFront(el)
	return el.Value.(*cacheEntry).data, true
}

func (c *blobCache) put(oid OID, data []byte) {
	if c.maxBytes <= 0 || int64(len(data)) > c.maxBytes {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()

	if el, ok := c.items[oid]; ok {
		c.ll.MoveToFront(el)
		el.Value.(*cacheEntry).data = data
		return
	}

	el := c.ll.PushFront(&cacheEntry{oid: oid, data: data})
	c.items[oid] = el
	c.curBytes += int64(len(data))

	for c.curBytes > c.maxBytes {
		back := c.ll.Back()
		if back == nil {
			break
		}
		entry := back.Value.(*cacheEntry)
		c.ll.Remove(back)
		delete(c.items, entry.oid)
		c.curBytes -= int64(len(entry.data))
	}
}
