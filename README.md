# Memory-sensitive Cache

A simple in-memory concurrent cache which grows with the available memory and starts evicting
entries when the JVM is under memory pressure. Thus, the cache shrinks when the memory is needed
elsewhere in the application. Unused memory is wasted memory. You might as well use it.

## Features

- Fully concurrent
- Evicts entries when the system is under memory pressure (see [Eviction](#eviction) below)
- Optionally retains hard references to the most recently used X entries (see [Hard references](#hard-references) below)

### Eviction

The cache uses `SoftReference` to hold the values. This means that the values are eligible for
garbage collection when the JVM is under memory pressure. The cache will grow until the point
where the JVM needs to reclaim memory, at which point the garbage collector will start
collecting the soft references and thus evicting entries from the cache.

The JVM's garbage collector does not guarantee a fully predictable collection order of
`SoftReference`s, but it is biased towards first-in-first-out. This means that the oldest entries
are more likely to be evicted first from this cache.

Also, note that because garbage collection happens
sporadically and in "chunks" there is no guarantee that only a minimal number of soft references
will be collected in each cycle. That is to say that the garbage collector may collect more than
is truly "needed" even if most garbage collectors attempt to shorten the collection time by not
necessarily collecting all what potentially _could_ be collected.

The JVM guarantees that an `OutOfMemoryError` will never be thrown unless all SoftReferences have been
collected first. 


### Hard references

As an additional feature, the cache can optionally prevent the most recently used N elements from being
evicted. The cache will retain a hard reference to these elements. This feature can work as a
countermeasure to the non-predictability of the cache's eviction as explained above: at least for
the N most recently used elements, the retention is guaranteed.

This feature is optional and can be configured when creating the cache, by setting `hardRefSize`
greater than zero. If set to zero, no hard references are kept, which means all entries in the
cache are (only) soft referenced. If set to a value greater than zero, some elements of the cache
will be both soft and hard referenced. Those elements which are (also) hard referenced are the most
recently used ones which means the sub-set of hard referenced values changes over time as elements 
are accessed or added to the cache.


### Background reaper thread

The cache has a background thread which periodically clears entries from the cache which
have been garbage collected. This is done to prevent the cache from growing indefinitely.
Garbage collected values take up the memory occupied by a Key and an empty object reference. 
For most caches, this is a very small memory footprint, because the objects used for keys tend to be small. 
Yet, it would still be a waste of memory to keep them around forever. This is the problem solved by 
the reaper thread.

As can be seen, because of the rather small memory consumption of these empty entries, the
reaper thread does not need to run <i>very</i> often. The default is once every 2 minutes,
but this can be configured in the constructor. The reaper thread is a daemon thread, so it
will not prevent the JVM from shutting down. Nevertheless, it is recommended to call the `close()`
method when the cache is no longer needed, to stop the reaper thread. If not, the reaper thread
will continue to run periodically until the JVM shuts down.


### Performance

The performance characteristics of this cache is similar to that of a `ConcurrentHashMap`.

## Usage

This cache is suitable where the requirement is that the cache should be able to expand with
the available memory and where it is acceptable that eviction is not fully predictable, but
at least _biased_ towards first-in-first-out.

You should close the cache when it is no longer needed by calling the `close()` method. If not,
the background thread will continue to run.


```java

import net.lbruun.cache.MemorySensitiveCache;

@Configuration
public class MyBean {

    // Spring will automatically call the close() method on this bean when the application context is closed.
    @Bean
    public MemorySensitiveCacheCache <Integer, String> cacheOfAllGoodThings() {
        // Create a cache which retains hard references to the 10 most recently used entries
        return new MemoryPressureCache<>(Integer.class, 10);
    }
}
```