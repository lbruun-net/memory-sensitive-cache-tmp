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

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.lang.Nullable;

/**
 * {@link CacheManager} implementation that lazily builds {@link SpringMemorySensitiveCache}
 * instances for each {@link #getCache} request. Also supports a 'static' mode where the set of
 * cache names is pre-defined through {@link #setCacheNames}, with no dynamic creation of further
 * cache regions at runtime.
 *
 * <p>Supports the asynchronous {@link Cache#retrieve(Object)} and {@link Cache#retrieve(Object,
 * Supplier)} operations through basic {@code CompletableFuture} adaptation, with early-determined
 * cache misses.
 *
 * @see SpringMemorySensitiveCache
 */
public class SpringMemorySensitiveCacheManager implements CacheManager {

  private final ConcurrentMap<String, Cache> cacheMap = new ConcurrentHashMap<>(16);

  private boolean dynamic = true;

  private boolean allowNullValues = true;

  /**
   * Construct a dynamic MyCacheManager, lazily creating cache instances as they are being
   * requested.
   */
  public SpringMemorySensitiveCacheManager() {}

  /** Construct a static MyCacheManager, managing caches for the specified cache names only. */
  public SpringMemorySensitiveCacheManager(String... cacheNames) {
    setCacheNames(Arrays.asList(cacheNames));
  }

  /**
   * Return whether this cache manager accepts and converts {@code null} values for all of its
   * caches.
   */
  public boolean isAllowNullValues() {
    return this.allowNullValues;
  }

  /**
   * Specify whether to accept and convert {@code null} values for all caches in this cache manager.
   *
   * <p>Default is "true", despite ConcurrentHashMap itself not supporting {@code null} values. An
   * internal holder object will be used to store user-level {@code null}s.
   *
   * <p>Note: A change of the null-value setting will reset all existing caches, if any, to
   * reconfigure them with the new null-value requirement.
   */
  public void setAllowNullValues(boolean allowNullValues) {
    if (allowNullValues != this.allowNullValues) {
      this.allowNullValues = allowNullValues;
      // Need to recreate all Cache instances with the new null-value configuration...
      recreateCaches();
    }
  }

  @Override
  public Collection<String> getCacheNames() {
    return Collections.unmodifiableSet(this.cacheMap.keySet());
  }

  /**
   * Specify the set of cache names for this CacheManager's 'static' mode.
   *
   * <p>The number of caches and their names will be fixed after a call to this method, with no
   * creation of further cache regions at runtime.
   *
   * <p>Calling this with a {@code null} collection argument resets the mode to 'dynamic', allowing
   * for further creation of caches again.
   */
  public void setCacheNames(@Nullable Collection<String> cacheNames) {
    if (cacheNames != null) {
      for (String name : cacheNames) {
        this.cacheMap.put(name, crateCache(name));
      }
      this.dynamic = false;
    } else {
      this.dynamic = true;
    }
  }

  @Override
  public @Nullable Cache getCache(String name) {
    Cache cache = this.cacheMap.get(name);
    if (cache == null && this.dynamic) {
      cache = this.cacheMap.computeIfAbsent(name, this::crateCache);
    }
    return cache;
  }

  /**
   * Remove the specified cache from this cache manager.
   *
   * @param name the name of the cache
   * @since 6.1.15
   */
  public void removeCache(String name) {
    this.cacheMap.remove(name);
  }

  private void recreateCaches() {
    for (Map.Entry<String, Cache> entry : this.cacheMap.entrySet()) {
      entry.setValue(crateCache(entry.getKey()));
    }
  }

  /**
   * Create a new Cache instance for the specified cache name.
   *
   * @param name the name of the cache
   * @return the ConcurrentMapCache (or a decorator thereof)
   */
  protected Cache crateCache(String name) {
    return new SpringMemorySensitiveCache(name, isAllowNullValues());
  }
}
