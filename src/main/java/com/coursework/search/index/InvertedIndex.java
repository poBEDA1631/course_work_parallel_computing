package com.coursework.search.index;

/**
 * Inverted index split into a power-of-two number of {@link IndexShard}s. A term lives in shard
 * {@code fnv1a(term) & (shardCount - 1)}.
 */
public final class InvertedIndex {
  private final IndexShard[] shards;
  private final int mask;

  /**
   * @param shardCount a power of two; the indexer uses the smallest power of two that is at least
   *     {@code 4 * buildThreads}, so that concurrent merges rarely contend for the same shard
   */
  public InvertedIndex(int shardCount) {
    if (shardCount < 1 || Integer.bitCount(shardCount) != 1) {
      throw new IllegalArgumentException("shardCount must be a power of two: " + shardCount);
    }
    shards = new IndexShard[shardCount];
    int shift = Integer.numberOfTrailingZeros(shardCount);
    for (int i = 0; i < shardCount; i++) {
      shards[i] = new IndexShard(shift);
    }
    mask = shardCount - 1;
  }

  public int shardCount() {
    return shards.length;
  }

  public int shardFor(String term) {
    return Fnv1a.hash32(term) & mask;
  }

  /**
   * Merges a worker's accumulated postings. Terms are bucketed by shard first, then shards are
   * visited in ascending order, each under its own write lock; at most one shard lock is held at a
   * time, so merges cannot deadlock.
   */
  public void mergeFrom(LocalAccumulator local) {
    String[] keys = local.keys();
    int[] hashes = local.hashes();
    Postings[] postings = local.postings();

    int[] starts = new int[shards.length + 1];
    for (int slot = 0; slot < keys.length; slot++) {
      if (keys[slot] != null) {
        starts[(hashes[slot] & mask) + 1]++;
      }
    }
    for (int s = 0; s < shards.length; s++) {
      starts[s + 1] += starts[s];
    }
    int[] order = new int[local.size()];
    int[] next = new int[shards.length];
    System.arraycopy(starts, 0, next, 0, shards.length);
    for (int slot = 0; slot < keys.length; slot++) {
      if (keys[slot] != null) {
        order[next[hashes[slot] & mask]++] = slot;
      }
    }

    for (int s = 0; s < shards.length; s++) {
      if (starts[s] < starts[s + 1]) {
        shards[s].mergeBatch(keys, hashes, postings, order, starts[s], starts[s + 1]);
      }
    }
  }

  /** Returns a snapshot of the term's postings, or {@code null} if the term is not indexed. */
  public Postings search(String term) {
    int hash = Fnv1a.hash32(term);
    return shards[hash & mask].lookup(term, hash);
  }

  /** Number of distinct terms; not an atomic snapshot while merges are running. */
  public int termCount() {
    int total = 0;
    for (IndexShard shard : shards) {
      total += shard.size();
    }
    return total;
  }
}
