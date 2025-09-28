# Memory-sensitive Cache
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/net.lbruun.cache/memorysensitive-cache/badge.svg)](https://maven-badges.herokuapp.com/maven-central/net.lbruun.cache/memorysensitive-cache)
[![javadoc](https://javadoc.io/badge2/net.lbruun.cache/memorysensitive-cache/javadoc.svg)](https://javadoc.io/doc/net.lbruun.cache/memorysensitive-cache)

A simple in-memory concurrent cache which grows with the available memory and starts evicting
entries when the JVM is under memory pressure. Thus, the cache shrinks when the memory is needed
elsewhere in the application. 

Unused memory is wasted memory. You might as well use it.

## Features

- Fully concurrent
- Evicts entries when the system is under memory pressure (see [Eviction](#eviction) below)
- Optionally retains hard references to the most recently used N entries (see [Hard references](#hard-references) below)

### Eviction when under memory pressure

The cache uses `SoftReference` to hold the values. This means that the values are eligible for
garbage collection when the JVM is under memory pressure. The cache will grow until the point
where the JVM needs to reclaim memory, at which point the garbage collector will start
collecting the soft references and thus evicting entries from the cache.

The JVM's garbage collector does not guarantee a fully predictable _collection order_ of the
`SoftReference`s, but it is biased towards first-in-first-out (FIFO). This means that the oldest entries
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

This feature is optional and can be configured when creating the cache by setting `hardRefSize`
greater than zero. If set to zero, no hard references are kept, which means all entries in the
cache are (only) soft referenced. If set to a value greater than zero, some elements of the cache
will be both soft and hard referenced. Those elements which are (also) hard referenced are the most
recently used ones which means the sub-set of hard referenced values changes over time as elements 
are accessed or added to the cache.


## Usage

This cache is suitable where the requirement is that the cache should be able to expand with
the available memory and where it is acceptable that eviction is not fully predictable, but
at least _biased_ towards first-in-first-out.

This type of cache is by no means suitable for any use case. The many standard cache implementations in the 
Java ecosystem (e.g. Caffeine, Ehcache, etc.) are more suitable for most use cases.


### Requirements

Java 17 or later.


### Maven Central coordinates

**groupId**: `net.lbruun.cache` \
**artifactId**: `memorysensitive-cache` \
**version**: check [Maven Central](https://search.maven.org/artifact/net.lbruun.cache/memorysensitive-cache)

### Documentation

See the [javadoc](https://javadoc.io/doc/net.lbruun.cache/memorysensitive-cache).


### Instantiate the cache

Here is a Spring example of how to instantiate the cache as a Spring bean:

```java
import net.lbruun.cache.MemorySensitiveCache;

@Configuration
public class MyConfig {

    @Bean
    public MemorySensitiveCacheCache <Integer, String> cacheOfAllGoodThings() {
        // Create a cache which retains hard references to the 100 most recently used entries
        return new MemoryPressureCache<>(Integer.class, 100);
    }
}
```
Also available are Spring Cache wrappers in the `net.lbruun.cache.spring` package. These are only
useful if you want to use Spring's `@Cacheable` annotation. If you don't need that, just use
the `MemorySensitiveCache` directly as shown above.
