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
package net.lbruun.cache.spring;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;
import net.lbruun.cache.MemorySensitiveCache;
import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Simple {@link org.springframework.cache.Cache Spring Cache} implementation for {@code
 * MemorySensitiveCache}.
 *
 * <p>Supports the {@link #retrieve(Object)} and {@link #retrieve(Object, Supplier)} operations in a
 * best-effort fashion, relying on default {@link CompletableFuture} execution (typically within the
 * JVM's {@link ForkJoinPool#commonPool()}).
 *
 * <p><b>Note:</b> As {@link MemorySensitiveCache} does not allow for {@code null} values to be
 * stored, this class will replace them with a predefined internal object. This behavior can be
 * changed through the {@link SpringMemorySensitiveCache(String, boolean)} constructor. However,
 * care should be taken when allowing {@code null} values, as there is no automatic eviction of such
 * entries, which may lead to memory leaks if not handled explicitly.
 *
 * @see SpringMemorySensitiveCacheManager
 */
public class SpringMemorySensitiveCache extends AbstractValueAdaptingCache {

  private final String name;

  private final MemorySensitiveCache<Object, Object> store;

  /**
   * Create a new SpringMemorySensitiveCache with the specified name.
   *
   * @param name the name of the cache
   */
  public SpringMemorySensitiveCache(String name) {
    this(name, new MemorySensitiveCache<>(Object.class, 10), true);
  }

  /**
   * Create a new SpringMemorySensitiveCache with the specified name.
   *
   * @param name the name of the cache
   * @param allowNullValues whether to accept and convert {@code null} values for this cache
   */
  public SpringMemorySensitiveCache(String name, boolean allowNullValues) {
    this(name, new MemorySensitiveCache<>(Object.class, 10), allowNullValues);
  }

  /**
   * Create a new SpringMemorySensitiveCache with the specified name and the given internal {@link
   * ConcurrentMap} to use.
   *
   * @param name the name of the cache
   * @param store the ConcurrentMap to use as an internal store
   * @param allowNullValues whether to allow {@code null} values (adapting them to an internal null
   *     holder value)
   */
  protected SpringMemorySensitiveCache(
      String name, MemorySensitiveCache<Object, Object> store, boolean allowNullValues) {

    super(allowNullValues);
    Assert.notNull(name, "Name must not be null");
    Assert.notNull(store, "Store must not be null");
    this.name = name;
    this.store = store;
  }

  @Override
  public final String getName() {
    return this.name;
  }

  @Override
  public final MemorySensitiveCache<Object, Object> getNativeCache() {
    return this.store;
  }

  @Override
  protected @Nullable Object lookup(Object key) {
    return this.store.get(key);
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> @Nullable T get(Object key, Callable<T> valueLoader) {
    return (T)
        fromStoreValue(
            this.store.computeIfAbsent(
                key,
                k -> {
                  try {
                    return toStoreValue(valueLoader.call());
                  } catch (Throwable ex) {
                    throw new ValueRetrievalException(key, valueLoader, ex);
                  }
                }));
  }

  @Override
  public @Nullable CompletableFuture<?> retrieve(Object key) {
    Object value = lookup(key);
    return (value != null
        ? CompletableFuture.completedFuture(
            isAllowNullValues() ? toValueWrapper(value) : fromStoreValue(value))
        : null);
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> CompletableFuture<T> retrieve(Object key, Supplier<CompletableFuture<T>> valueLoader) {
    return CompletableFuture.supplyAsync(
        () ->
            (T)
                fromStoreValue(
                    this.store.computeIfAbsent(key, k -> toStoreValue(valueLoader.get().join()))));
  }

  @Override
  public void put(Object key, @Nullable Object value) {
    this.store.put(key, toStoreValue(value));
  }

  @Override
  public @Nullable ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
    Object existing = this.store.putIfAbsent(key, toStoreValue(value));
    return toValueWrapper(existing);
  }

  @Override
  public void evict(Object key) {
    this.store.remove(key);
  }

  @Override
  public boolean evictIfPresent(Object key) {
    boolean existed = this.store.contains(key);
    this.store.remove(key);
    return existed;
  }

  @Override
  public void clear() {
    this.store.clear();
  }

  @Override
  public boolean invalidate() {
    boolean notEmpty = !this.store.isEmpty();
    this.store.clear();
    return notEmpty;
  }
}
