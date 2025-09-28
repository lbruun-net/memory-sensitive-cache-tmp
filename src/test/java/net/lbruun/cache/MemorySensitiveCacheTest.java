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

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class MemorySensitiveCacheTest {
  @Test
  void get() {
    MemorySensitiveCache<Integer, String> cache = new MemorySensitiveCache<>(Integer.class, 10);
    cache.put(1, "1");
    cache.put(2, "2");
    cache.put(3, "3");

    assertEquals(3, cache.size());

    String s = cache.get(1);
    assertEquals("1", s);
  }

  @Test
  void testPutAndGet() {
    MemorySensitiveCache<Integer, String> cache = new MemorySensitiveCache<>(Integer.class, 2);
    cache.put(1, "one");
    cache.put(2, "two");
    assertEquals("one", cache.get(1));
    assertEquals("two", cache.get(2));
    assertEquals(2, cache.size());
  }

  @Test
  void computeIfAbsent() {}

  @Test
  void remove() {
    MemorySensitiveCache<Integer, String> cache = new MemorySensitiveCache<>(Integer.class, 2);
    cache.put(1, "1");

    assertEquals(1, cache.hardCacheSize());
    cache.put(2, "2");
    assertEquals(2, cache.hardCacheSize());
    cache.put(3, "3");
    cache.put(4, "4");
    cache.put(5, "5");
    cache.put(6, "6");
    assertEquals(2, cache.hardCacheSize());

    assertEquals(6, cache.size());

    cache.remove(1);
    assertEquals(5, cache.size());

    cache.remove(6);
    assertEquals(4, cache.size());
    assertEquals(1, cache.hardCacheSize());
  }

  @Test
  void removeIfEmpty() {
    MemorySensitiveCache<Integer, byte[]> cache = new MemorySensitiveCache<>(Integer.class, 1);
    cache.put(1, randomBytes(1000));
    cache.put(2, randomBytes(1000));
    cache.put(3, randomBytes(1000));

    assertEquals(3, cache.size());
    assertNotNull(cache.get(1));
    assertNotNull(cache.get(2));
    assertNotNull(cache.get(3));

    applyMemoryPressure();

    assertNull(cache.removeIfEmpty(1));
    assertEquals(1, cache.size());

    // The last inserted should be retained in the hard cache
    assertNotNull(cache.removeIfEmpty(3));
  }

  @Test
  void putIfAbsent() throws InterruptedException {
    MemorySensitiveCache<Integer, String> cache = new MemorySensitiveCache<>(Integer.class, 1);
    cache.put(1, "ABC");
    cache.put(2, "DEF");
    cache.put(3, "GHI");
    assertEquals(3, cache.size());

    String existing1 = cache.putIfAbsent(3, "NEW");
    assertEquals("GHI", existing1);
    assertEquals("GHI", cache.get(3));

    String existing2 = cache.putIfAbsent(4, "NEW");
    assertNull(existing2);
    assertEquals("NEW", cache.get(4));
  }

  @Test
  void clear() {}

  @Test
  void test_memoryPressure() {
    try {
      MemorySensitiveCache<Integer, byte[]> cache = new MemorySensitiveCache<>(Integer.class, 0);
      cache.put(1, randomBytes(10000000));
      cache.put(2, randomBytes(10000000));
      cache.put(3, randomBytes(10000000));

      assertEquals(3, cache.size());

      applyMemoryPressure();

      Thread.sleep(6000);
      assertEquals(0, cache.size());

    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void test_memoryPressure2() throws InterruptedException {
    MemorySensitiveCache<Integer, byte[]> cache = new MemorySensitiveCache<>(Integer.class, 0);

    // System.gc();
    // Thread.sleep(1000);
    long maxMemory = Runtime.getRuntime().maxMemory();
    System.out.println("Max heap memory: " + maxMemory);
    int chunkSize = 1024 * 1024;

    // long iterations = (maxMemory / chunkSize) + 200;
    long iterations = 2500;
    System.out.println("No of 'put' operations: " + iterations);
    for (int i = 0; i < iterations; i++) {
      cache.put(i, new byte[chunkSize]);
    }

    int size = cache.size();
    System.out.println(
        "No of cache entries: "
            + size
            + " (but could have been as much as "
            + (maxMemory / chunkSize)
            + ")");

    applyMemoryPressure();
    size = cache.size();
    System.out.println(
        "No of cache entries: "
            + size
            + " (but could have been as much as "
            + (maxMemory / chunkSize)
            + ")");
    assertTrue(size < iterations);
  }

  @Test
  @Disabled
  void test_memoryPressureForever() throws InterruptedException {

    MemorySensitiveCache<Integer, byte[]> cache = new MemorySensitiveCache<>(Integer.class, 100);

    Random rand = new Random();
    int min = 1024 * 1;
    // int max = 1024 * 1024 * 25;
    int max = 1024 * 100;

    int i = 0;
    while (true) {
      byte[] chunk = getChunk(rand, min, max);
      cache.put(i, chunk);
      i++;
    }
  }

  // Simulate memory pressure (twice)
  // Purpose is to make sure that SoftReferences are GC'ed.
  private void applyMemoryPressure() {
    applyMemoryPressure0();
    applyMemoryPressure0();
  }

  private void applyMemoryPressure0() {
    List<byte[]> memoryHog = new ArrayList<>();
    int chunkSize = 1024 * 1024 * 100; // Allocate 100MB chunks
    try {
      while (true) {
        memoryHog.add(new byte[chunkSize]);
      }
    } catch (OutOfMemoryError ignored) {
      memoryHog = null;
      return;
    }
  }

  private byte[] randomBytes(int len) {
    byte[] bytes = new byte[len];
    new Random().nextBytes(bytes);
    return bytes;
  }

  private int randomInt(Random random, int min, int max) {
    return random.nextInt(max - min + 1) + min;
  }

  private byte[] getChunk(Random random, int min, int max) {
    int len = randomInt(random, min, max);

    try {
      return new byte[len];
    } catch (OutOfMemoryError oome) {
      return new byte[0];
    }
  }
}
