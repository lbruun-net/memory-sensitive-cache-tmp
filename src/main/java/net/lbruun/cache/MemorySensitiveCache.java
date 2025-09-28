/*
 * Copyright 2025 lbruun.net.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.lbruun.cache;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Concurrent cache which evicts elements when there is memory pressure on the application. The
 * cache is only bounded by the available memory. The values of the cache are based on {@link
 * SoftReference} and are therefore eligible for garbage collection. Thus, the cache will grow up
 * until the point where there is no longer available memory to expand the cache. Also, the cache
 * will automatically shrink when memory is needed elsewhere in the application.
 *
 * <p>This cache is suitable where the requirement is that the cache should be able to expand with
 * the available memory and where it is acceptable that eviction is not fully predictable, but
 * biased towards first-in-first-out. Also, note that because garbage collection happens
 * sporadically and in "chunks" there is no guarantee that only a minimal number of soft references
 * will be collected in each cycle. That is to say that the garbage collector may collect more than
 * is truly "needed" even if most garbage collectors attempt to shorten the collection time by not
 * necessarily collecting all what potentially <i>could</i> be collected.
 *
 * <p>As an additional feature, the cache can prevent the most recently used N elements from being
 * evicted. The cache will retain a hard reference to these elements. This feature can work as a
 * countermeasure to the non-predictability of the cache's eviction as explained above: at least for
 * the N most recently used elements, the retention is guaranteed.
 *
 * <p>Entries that have been garbage collected remain in the cache using the memory consumption of a
 * key and an empty reference. Not a lot, but the cache would grow indefinitely if such empty
 * entries were not removed. Therefore, such entries are removed from the cache on every "touch" of
 * the cache, i.e. read, write or "size" operations. This is a very, very fast operation and can
 * hardly show in measurements. However, garbage collection often occurs in "bursts", meaning that
 * it may happen there is suddenly a large bunch of entries that need to be expunged. Theoretically,
 * this can block the current cache operation for a short period of time, say 2ms. In practice, this
 * has not been observed to be a problem. Even so, the behavior can be tuned via the {@code
 * maxExpungePerReadOperation} parameter in the constructor. The default value of 100 means that at
 * most 100 GC'ed entries will be expunged per read operation. By contrast, write operations and
 * "size" operations will always expunge all GC'ed entries in one go.
 *
 * <p>The cache is fully thread-safe.
 *
 * <p>The cache only allows non-null values.
 *
 * <p>Behind the scenes, the cache uses a {@link ConcurrentHashMap} and therefore has roughly the
 * same performance characteristics as that class, with the two minor exceptions:
 *
 * <ul>
 *   <li>On every "touch" of the cache housekeeping (expunging GC'ed elements) is performed as
 *       explained above. However, this is very fast and should not be measurable in practice.
 *   <li>If the hardcache feature is used ({@code hardRefSize} &gt; 0) then there is a little
 *       overhead associated with maintaining a set of pointers to the most recently used cache
 *       elements.
 * </ul>
 *
 * @param <K> key type
 * @param <V> value type
 */
public class MemorySensitiveCache<K, V> {

  /** Indicates to use the default initial capacity of ConcurrentHashMap , whatever that may be. */
  public static final int USE_DEFAULT_MAP_INITIAL_CAPACITY = -1;

  private static final Object SENTINEL = new Object();
  private final ConcurrentMap<K, SoftValue<K, V>> map;
  private final ReferenceQueue<V> referenceQueue = new ReferenceQueue<>();
  private final SimpleQueue<K, V> hardCache;
  private final Class<K> keyClass;
  private final int maxExpungePerReadOperation;

  /**
   * Creates a cache.
   *
   * @param keyClass class for the key
   * @param hardRefSize number of elements which are "hard referenced" meaning they are never GC'ed.
   *     Value must be &ge; 0. Setting this value too high may result in an eventual {@link
   *     OutOfMemoryError} during operation of the cache which is otherwise guaranteed not to happen
   *     with this cache. Therefore, the value should be reasonably low, however dependent on the
   *     expected size of values added to the cache, available heap memory, etc. Setting the value
   *     to zero guarantees that the cache will never throw {@link OutOfMemoryError} at the cost of
   *     some unpredictability as to how eviction occurs. A value of 100 may be a reasonable value
   *     for many scenarios.
   * @param initialCapacity the initial capacity of the map used by this cache. If {@link
   *     #USE_DEFAULT_MAP_INITIAL_CAPACITY} then the JDK's default value will be used, typically 16.
   *     See {@link ConcurrentHashMap#ConcurrentHashMap(int)} for more information.
   * @param maxExpungePerReadOperation Maximum number of GC'ed entries to expunge from the cache on
   *     a single "touch" of the cache. This applies to read-operations only (i.e. get, contains,
   *     etc). Write operations and "size" operations will always expunge all GC'ed entries in one
   *     go. The value must be &ge; 0. Set to {@link Integer#MAX_VALUE} to effectively disable the
   *     feature. Default value is 100.
   */
  public MemorySensitiveCache(
      final Class<K> keyClass,
      int hardRefSize,
      int initialCapacity,
      int maxExpungePerReadOperation) {
    Objects.requireNonNull(keyClass, "keyClass must not be null");

    this.keyClass = keyClass;
    this.map =
        (initialCapacity == USE_DEFAULT_MAP_INITIAL_CAPACITY)
            ? new ConcurrentHashMap<>()
            : new ConcurrentHashMap<>(initialCapacity);
    this.hardCache =
        (hardRefSize == 0) ? new SimpleQueueNoOpImpl<>() : new SimpleQueueImpl<>(hardRefSize);
    this.maxExpungePerReadOperation = maxExpungePerReadOperation;
  }

  /**
   * Creates a cache with default initial capacity.
   *
   * @param keyClass class for the key
   * @param hardRefSize number of elements which are "hard referenced" meaning they are never GC'ed.
   *     (value must be &ge; 0)
   * @see #MemorySensitiveCache(Class, int, int, int)
   */
  public MemorySensitiveCache(final Class<K> keyClass, int hardRefSize) {
    this(keyClass, hardRefSize, USE_DEFAULT_MAP_INITIAL_CAPACITY, 100);
  }

  /**
   * Removes stale entries from the map. Stale entries are entries whose values have been garbage
   * collected.
   *
   * <p>The method is invoked for every access to the cache. According to the JDK's Javadoc (see
   * https://docs.oracle.com/en/java/javase/24/docs/api/java.base/java/lang/ref/package-summary.html#notification-heading)
   * this is very fast and should not be a performance issue. It is also how WeakHashMap does it.
   * This has also been verified by oww measurements.
   */
  private void expungeStaleEntries(boolean allowBlocking) {
    // Remove objects which have been GC'ed
    int count = 0;

    // Avoid handling bursts of garbage collected objects in one go for read operations
    // (allowBlocking = false) ... in contrast to write operations (allowBlocking = true) where
    // all stale entries are handled in one go.
    // It is debatable if the split should be 100 or some other number. It is also debatable if
    // there should be a split at all since indeed it seems to be a very fast operation.
    while ((allowBlocking) || (count < this.maxExpungePerReadOperation)) {
      Reference<? extends V> ref = referenceQueue.poll();
      if (ref == null) {
        break;
      }
      count++;

      // This will indeed always be the case
      if (ref instanceof SoftValue<?, ?> softValue) {
        // In principle (and reality, because we're not using the ReferenceQueue for
        // any other purpose), the key is always of type K, but type information was
        // lost due to type erasure.
        Object oKey = softValue.key;
        if (keyClass.isInstance(oKey)) {
          K key = keyClass.cast(oKey);
          // The value may theoretically have entered into the cache (again)
          // in the meantime. So remove _conditionally_.
          removeIfEmpty(key, false);
        }
      }
    }
  }

  /**
   * Get value from the cache.
   *
   * @param key the key whose associated value is to be returned (not {@code null})
   * @return value or {@code null} if no such value exists
   */
  public V get(K key) {
    Objects.requireNonNull(key, "key cannot be null");
    expungeStaleEntries(false);
    SoftValue<K, V> softValue =
        map.compute(
            key,
            (k, sv) -> {
              if (sv == null) {
                // No existing mapping
                return null;
              } else {
                V v = sv.get();
                if (v == null) {
                  // Existing mapping, but it points to an empty SoftReference so effectively not
                  // present
                  return sv;
                } else {
                  // Existing mapping ==> Nothing needs to be done except to record usage
                  // in the hardcache to make sure it goes to the top of the stack
                  hardCache.touch(key, v);
                  return sv;
                }
              }
            });

    if (softValue == null) {
      return null;
    } else {
      V v = softValue.get();
      if (v == null) {
        return removeIfEmpty(key);
      } else {
        return v;
      }
    }
  }

  /**
   * Checks if a value for {@code key} exists in the cache.
   *
   * @param key the key whose presence in this cache is to be tested (not {@code null})
   * @return true if a value for {@code key} exists in the cache, false otherwise
   */
  public boolean contains(K key) {
    // Never return 'true' for values which are empty (i.e. "marked for deletion")
    return (get(key) != null);
  }

  /**
   * Gets the current number of elements in the hard cache (which is a sub-set of the total cache).
   * These elements are guaranteed to not be garbage collected.
   *
   * @see #size()
   * @return number of elements in the hard cache. The number will be &ge; 0 and &le; the configured
   *     hard cache size as per the {@code hardRefSize} in the constructor.
   */
  public int hardCacheSize() {
    return hardCache.size();
  }

  /**
   * If the specified key is not already present in the cache, associate it with the given value.
   *
   * <p>The action is performed atomically.
   *
   * @param key key with which the specified value is to be associated (not {@code null})
   * @param value value to be associated with the specified key if no value already exists (not
   *     {@code null})
   * @return existing value associated with the specified key, or {@code null} if no such value
   *     existed.
   */
  public V putIfAbsent(K key, V value) {
    Objects.requireNonNull(key, "key cannot be null");
    Objects.requireNonNull(value, "value cannot be null");
    expungeStaleEntries(true);

    Object[] holder = new Object[] {SENTINEL};
    map.compute(
        key,
        (k, sv) -> {
          if (sv == null) {
            return compute00(key, k1 -> value, false);
          } else {
            V v = sv.get();
            if (v == null) {
              // Existing mapping, but it points to an empty SoftReference ==> compute new
              return compute00(key, k1 -> value, false);
            } else {
              // Existing mapping ==> Nothing needs to be done except to record usage
              // in the hardcache to make sure it goes to the top of the stack
              holder[0] = v; // remember existing value
              hardCache.touch(key, v);
              return sv;
            }
          }
        });

    if (holder[0] == SENTINEL) {
      return null;
    } else {
      @SuppressWarnings("unchecked")
      V existing = (V) holder[0];
      return existing;
    }
  }

  /**
   * If the specified key is not already present in the cache, attempts to compute its value using
   * the given mapping function and enters it into the cache unless {@code null}. The entire method
   * invocation is performed atomically. The supplied function is invoked exactly once per
   * invocation of this method if the key is absent, else not at all.
   *
   * <p>If the key already has a value then the only (but important) consequence is that "usage" is
   * recorded
   *
   * @param key key with which the value is to be associated
   * @param mappingFunction function which returns V using K as input. The function must not return
   *     {@code null}. The function must not interact with the cache.
   * @return the current (existing or computed) value associated with the specified key, never
   *     {@code null}.
   * @throws NullPointerException if any input is null
   * @throws IllegalArgumentException if the supplied mapping function returns {@code null}
   * @throws RuntimeException (or subclass) any unchecked exception resulting from invocation of
   *     mapping function it is rethrown as-is.
   */
  public V computeIfAbsent(K key, Function<K, V> mappingFunction) {
    Objects.requireNonNull(key, "key cannot be null");
    Objects.requireNonNull(mappingFunction, "mappingFunction cannot be null");
    expungeStaleEntries(false); // is is read or write? We don't know, so let's say read

    // Atomically check if the key is already associated with a value.
    // If so, check if the value is empty. In this case, treat as if the value
    // is not present in the cache. This is the reason why map.compute() must be used
    // and not map.computeIfAbsent(), because the latter has no knowledge about what we
    // really mean by 'absent': our definition of 'absent' also includes the case when the
    // association exists in the map however with an empty value.
    SoftValue<K, V> softValue =
        map.compute(
            key,
            (k, sv) -> {
              if (sv == null) {
                // No existing mapping ==> compute new
                return compute00(key, mappingFunction, false);
              } else {
                V v = sv.get();
                if (v == null) {
                  // Existing mapping, but it points to an empty SoftReference ==> compute new
                  return compute00(key, mappingFunction, false);
                } else {
                  // Existing mapping ==> Nothing needs to be done except to record usage
                  // in the hardcache to make sure it goes to the top of the stack
                  hardCache.touch(key, v);
                  return sv;
                }
              }
            });
    assert softValue != null; // the above guarantees a non-null result
    V v = softValue.get();

    // Let's do a sanity check
    if (v == null) {
      // The value has already been GC'ed !
      // (in the meantime)
      return removeIfEmpty(key);
    } else {
      // The normal case
      return v;
    }
  }

  /**
   * Puts a value into the cache. If the cache already contains a value for the given key, the value
   * will be replaced.
   */
  public void put(K key, V value) {
    Objects.requireNonNull(key, "key cannot be null");
    Objects.requireNonNull(value, "value cannot be null");
    compute0(key, k -> value);
  }

  /**
   * Removes a value from the cache.
   *
   * @param key
   */
  public void remove(K key) {
    Objects.requireNonNull(key, "key cannot be null");
    compute0(key, k -> null);
  }

  /**
   * Conditionally (and atomically) remove a value from the cache if its associated value has been
   * garbage collected. The method is mainly useful in unit tests.
   *
   * <p>The returned value depends on the scenario:
   *
   * <ul>
   *   <li>The key exist in the cache, but the value has been garbage collected (is empty). In this
   *       case {@code null} is returned. The association is removed from the cache.
   *   <li>The key does not exist in the cache: In this case {@code null} is returned. The cache is
   *       unchanged.
   *   <li>The key exist in the cache with a value which has not been garbage collected (is
   *       non-empty): In this case, the value is returned and will always be non-null. The cache is
   *       unchanged.
   * </ul>
   *
   * @param key key whose entry is to be removed from the cache
   * @return value or {@code null}, see above for details
   */
  public final V removeIfEmpty(K key) {
    return removeIfEmpty(key, true);
  }

  /** Clears the cache completely. */
  public synchronized void clear() {
    while (true) { // drain the reference queue
      Reference<? extends V> element = referenceQueue.poll();
      if (element == null) {
        break;
      }
    }
    map.clear();
    hardCache.clear();
  }

  /**
   * Gets the size of the cache.
   *
   * <p>The method has constant time characteristics, <i>O(1)</i>.
   *
   * @see #hardCacheSize()
   * @return number of entries in the cache.
   */
  public int size() {
    expungeStaleEntries(true);
    return map.size();
  }

  /**
   * Checks if the cache is empty.
   *
   * @return {@code true} if the cache is empty, {@code false} otherwise
   */
  public boolean isEmpty() {
    return (size() == 0);
  }

  /**
   * Iterator over the entries in the cache. (read-only)
   *
   * <p>The iterator is <i>weakly consistent</i>, as it is based on {@link
   * ConcurrentHashMap#values() ConcurrentHashMap's iterator}.
   *
   * <p>The iterator does not support element removal.
   *
   * @return iterator
   */
  public Iterator<KeyValuePair<K, V>> iterator() {
    expungeStaleEntries(true);

    final Iterator<SoftValue<K, V>> baseIterator = map.values().iterator();

    return new Iterator<>() {
      KeyValuePair<K, V> nextMatch = null;
      boolean nextHasEvaluated = false;

      @Override
      public boolean hasNext() {
        if (nextHasEvaluated) return nextMatch != null;

        while (baseIterator.hasNext()) {
          try {
            SoftValue<K, V> candidate = baseIterator.next();
            V v = candidate.get();
            if (v != null) { // skip garbage collected values
              nextMatch = new KeyValuePair<>(candidate.key, v);
              nextHasEvaluated = true;
              return true;
            }
          } catch (NoSuchElementException ignored) { // should not happen, but just in case
            break;
          }
        }
        nextMatch = null;
        nextHasEvaluated = true;
        return false;
      }

      @Override
      public KeyValuePair<K, V> next() {
        if (!hasNext()) throw new NoSuchElementException();
        nextHasEvaluated = false;
        return nextMatch;
      }
    };
  }

  // Only to be called from inside a map.compute() call
  private SoftValue<K, V> compute00(
      K key, Function<K, V> mappingFunction, boolean allowRemoveByNull) {
    V value = mappingFunction.apply(key);
    if (value == null) {
      // null means 'remove'
      if (!allowRemoveByNull) {
        throw new IllegalArgumentException("mappingFunction returned null");
      } else {
        hardCache.remove(key); // It may exist in the hardcache, so need to be removed there too
        return null;
      }
    } else {
      hardCache.add(key, value);
      return new SoftValue<>(key, value, referenceQueue);
    }
  }

  private void compute0(K key, Function<K, V> mappingFunction) {
    expungeStaleEntries(true);
    map.compute(key, (k, v) -> compute00(key, mappingFunction, true));
  }

  private V removeIfEmpty(K key, boolean performHousekeeping) {
    Objects.requireNonNull(key, "key cannot be null");
    SoftValue<K, V> softValue =
        map.computeIfPresent(
            key,
            (k, sRef) -> {
              if (sRef.get() == null) {
                return null;
              } else {
                return sRef;
              }
            });
    if (softValue != null) {
      return softValue.get();
    }
    return null;
  }

  /** Deque of key-value references. */
  private static interface SimpleQueue<K, V> {
    void add(K key, V value);

    void touch(K key, V value);

    void remove(K key);

    void clear();

    int size();
  }

  /**
   * We define our own subclass of SoftReference which contains not only the value but also the key
   * to make it easier to find the entry in the Map after it has been garbage collected.
   */
  private static final class SoftValue<K, V> extends SoftReference<V> {

    private final K key;

    private SoftValue(K key, V value, ReferenceQueue<? super V> queue) {
      super(value, queue);
      this.key = key;
    }
  }

  /** No-op implementation of SimpleDeque. */
  private static class SimpleQueueNoOpImpl<K, V> implements SimpleQueue<K, V> {

    @Override
    public void add(K key, V value) {}

    @Override
    public void touch(K key, V value) {}

    @Override
    public void remove(K key) {}

    @Override
    public void clear() {}

    @Override
    public int size() {
      return 0;
    }
  }

  /**
   * Queue of key-value references. The purpose is to keep hard references to a bounded collection
   * of objects, at most {@code maxSize} objects, so that these are not garbage collected. This is
   * useful when working with collections of {@link SoftReference}.
   *
   * <p>The deque works as a FIFO queue: when it reaches its maximum size, the element inserted
   * first will be removed.
   *
   * <p>The class is thread-safe.
   */
  private static class SimpleQueueImpl<K, V> implements SimpleQueue<K, V> {
    private final Queue<KeyValuePair<K, V>> queue;

    public SimpleQueueImpl(final int maxSize) {
      if (maxSize < 0) {
        throw new IllegalArgumentException("maxSize must be >= 0");
      }
      this.queue = new LinkedBlockingQueue<>(maxSize);
    }

    /**
     * Adds an element. If there is already {@code maxSize} elements in the stack then the oldest
     * (by insertion time) will be removed.
     */
    @Override
    public void add(K key, V value) {
      Objects.requireNonNull(key, "key cannot be null");
      Objects.requireNonNull(value, "value cannot be null");
      KeyValuePair<K, V> element = new KeyValuePair<>(key, value);
      while (!queue.offer(element)) {
        queue.poll();
      }
    }

    @Override
    public void touch(K key, V value) {
      remove(key);
      add(key, value);
    }

    @Override
    public void remove(K key) {
      Objects.requireNonNull(key, "key cannot be null");
      Iterator<KeyValuePair<K, V>> iterator = queue.iterator();
      while (iterator.hasNext()) {
        KeyValuePair<K, V> pair = iterator.next();
        if (pair.key().equals(key)) {
          iterator.remove();
          break; // Remove only the first. In practice, there will never be more than one
        }
      }
    }

    /** Removes all elements from the stack. */
    @Override
    public void clear() {
      queue.clear();
    }

    /**
     * Gets the current number of elements in the stack.
     *
     * @return size
     */
    @Override
    public int size() {
      return queue.size();
    }
  }

  /** Simple key-value pair. */
  public record KeyValuePair<K, V>(K key, V value) {}
}
