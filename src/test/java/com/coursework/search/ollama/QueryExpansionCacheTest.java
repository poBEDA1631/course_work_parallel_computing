package com.coursework.search.ollama;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class QueryExpansionCacheTest {
  @Test
  void evictsTheLeastRecentlyInsertedWhenNothingWasRead() {
    QueryExpansionCache cache = new QueryExpansionCache(3);
    put(cache, "a", "b", "c", "d");
    assertNull(cache.get("a"));
    assertArrayEquals(new String[] {"d", "c", "b"}, cache.keysByRecency());
  }

  @Test
  void getRefreshesSoEvictionFollowsUseNotInsertion() {
    QueryExpansionCache cache = new QueryExpansionCache(3);
    put(cache, "a", "b", "c");
    assertEquals(List.of("a!"), cache.get("a")); // "a" was inserted first but is now the freshest
    put(cache, "d");
    assertNull(cache.get("b"));
    assertEquals(List.of("a!"), cache.get("a"));
    assertArrayEquals(new String[] {"a", "d", "c"}, cache.keysByRecency());
  }

  @Test
  void putOnExistingKeyReplacesValueAndRefreshes() {
    QueryExpansionCache cache = new QueryExpansionCache(2);
    put(cache, "a", "b");
    cache.put("a", List.of("new"));
    put(cache, "c");
    assertNull(cache.get("b"));
    assertEquals(List.of("new"), cache.get("a"));
    assertEquals(2, cache.size());
  }

  @Test
  void capacityOneKeepsOnlyTheLastKey() {
    QueryExpansionCache cache = new QueryExpansionCache(1);
    put(cache, "a", "b");
    assertNull(cache.get("a"));
    assertEquals(List.of("b!"), cache.get("b"));
    assertArrayEquals(new String[] {"b"}, cache.keysByRecency());
  }

  @Test
  void storesAnUnmodifiableCopy() {
    QueryExpansionCache cache = new QueryExpansionCache(2);
    List<String> terms = new ArrayList<>(List.of("x"));
    cache.put("k", terms);
    terms.add("y");
    assertEquals(List.of("x"), cache.get("k"));
    assertThrows(UnsupportedOperationException.class, () -> cache.get("k").add("z"));
  }

  @Test
  void defaultCapacityIs256() {
    QueryExpansionCache cache = new QueryExpansionCache();
    for (int i = 0; i < 300; i++) {
      cache.put("q" + i, List.of());
    }
    assertEquals(256, cache.size());
    assertNull(cache.get("q43"));
    assertEquals(List.of(), cache.get("q44"));
  }

  @Test
  void rejectsNonPositiveCapacity() {
    assertThrows(IllegalArgumentException.class, () -> new QueryExpansionCache(0));
  }

  @Test
  void concurrentGetsAndPutsKeepMapAndListConsistent() throws Exception {
    int capacity = 64;
    int keys = 200;
    QueryExpansionCache cache = new QueryExpansionCache(capacity);
    int threads = 8;
    CountDownLatch start = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread[] workers = new Thread[threads];
    for (int t = 0; t < threads; t++) {
      long seed = t;
      workers[t] =
          new Thread(
              () -> {
                SplittableRandom random = new SplittableRandom(seed);
                try {
                  start.await();
                  for (int i = 0; i < 200_000; i++) {
                    String key = "k" + random.nextInt(keys);
                    if (random.nextBoolean()) {
                      cache.put(key, List.of(key + "!"));
                    } else {
                      List<String> value = cache.get(key);
                      if (value != null && !value.equals(List.of(key + "!"))) {
                        throw new AssertionError("wrong value " + value + " for " + key);
                      }
                    }
                  }
                } catch (Throwable e) {
                  failure.compareAndSet(null, e);
                }
              });
      workers[t].start();
    }
    start.countDown();
    for (Thread worker : workers) {
      worker.join();
    }
    if (failure.get() != null) {
      throw new AssertionError("worker failed", failure.get());
    }
    assertEquals(capacity, cache.size());
    String[] order = cache.keysByRecency();
    assertEquals(capacity, order.length);
    for (String key : order) {
      assertEquals(List.of(key + "!"), cache.get(key));
    }
  }

  private static void put(QueryExpansionCache cache, String... keys) {
    for (String key : keys) {
      cache.put(key, List.of(key + "!"));
    }
  }
}
