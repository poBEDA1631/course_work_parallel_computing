package com.coursework.search.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IndexShardTest {
  @Test
  void rehashesExactlyWhenLoadFactorWouldExceedSevenTenths() {
    IndexShard shard = new IndexShard(0);
    int threshold = IndexShard.INITIAL_CAPACITY * 7 / 10;
    for (int i = 0; i < threshold; i++) {
      shard.incrementOrCreate("t" + i, i, 1);
    }
    assertEquals(IndexShard.INITIAL_CAPACITY, shard.capacity());
    shard.incrementOrCreate("t" + threshold, threshold, 1);
    assertEquals(IndexShard.INITIAL_CAPACITY * 2, shard.capacity());
  }

  @Test
  void manyInsertsSurviveRepeatedRehashing() {
    IndexShard shard = new IndexShard(0);
    int terms = 10_000;
    for (int i = 0; i < terms; i++) {
      shard.incrementOrCreate("term" + i, i, i % 7 + 1);
    }
    assertEquals(terms, shard.size());
    int capacity = shard.capacity();
    assertEquals(1, Integer.bitCount(capacity));
    assertTrue(shard.size() * 10L <= capacity * 7L);
    for (int i = 0; i < terms; i++) {
      Postings p = shard.lookup("term" + i);
      assertNotNull(p, "term" + i);
      assertEquals(1, p.size());
      assertEquals(i, p.docId(0));
      assertEquals(i % 7 + 1, p.termFrequency(0));
    }
    assertNull(shard.lookup("absent"));
  }

  @Test
  void repeatedTermAppendsToExistingPostings() {
    IndexShard shard = new IndexShard(0);
    shard.incrementOrCreate("x", 1, 2);
    shard.incrementOrCreate("x", 5, 3);
    assertEquals(1, shard.size());
    Postings p = shard.lookup("x");
    assertEquals(2, p.size());
    assertEquals(5, p.docId(1));
    assertEquals(3, p.termFrequency(1));
  }

  @Test
  void collidingKeysProbeLinearlyAndWrapAroundTheTable() {
    int shift = 3;
    int lastSlot = IndexShard.INITIAL_CAPACITY - 1;
    String[] colliding = keysInSlot(shift, lastSlot, IndexShard.INITIAL_CAPACITY, 7);
    IndexShard shard = new IndexShard(shift);
    for (int i = 0; i < 6; i++) {
      shard.incrementOrCreate(colliding[i], i, i + 1);
    }
    assertEquals(IndexShard.INITIAL_CAPACITY, shard.capacity());
    for (int i = 0; i < 6; i++) {
      Postings p = shard.lookup(colliding[i]);
      assertNotNull(p, colliding[i]);
      assertEquals(i, p.docId(0));
      assertEquals(i + 1, p.termFrequency(0));
    }
    assertNull(shard.lookup(colliding[6]));

    for (int i = 0; i < 100; i++) {
      shard.incrementOrCreate("filler" + i, 1000 + i, 1);
    }
    assertTrue(shard.capacity() > IndexShard.INITIAL_CAPACITY);
    for (int i = 0; i < 6; i++) {
      assertEquals(i, shard.lookup(colliding[i]).docId(0));
    }
    assertNull(shard.lookup(colliding[6]));
  }

  private static String[] keysInSlot(int shift, int slot, int capacity, int count) {
    String[] found = new String[count];
    int n = 0;
    for (int i = 0; n < count; i++) {
      String key = "k" + i;
      if (((Fnv1a.hash32(key) >>> shift) & (capacity - 1)) == slot) {
        found[n++] = key;
      }
    }
    return found;
  }
}
