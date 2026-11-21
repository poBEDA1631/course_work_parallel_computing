package com.coursework.search.index;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Open-addressing hash table with linear probing, guarded by its own read-write lock. */
final class IndexShard {
  static final int INITIAL_CAPACITY = 16;

  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  /**
   * The low hash bits already chose this shard, so every key here shares them; slots are taken from
   * the remaining high bits to keep keys spread across the table.
   */
  private final int hashShift;

  private String[] keys = new String[INITIAL_CAPACITY];
  private Postings[] values = new Postings[INITIAL_CAPACITY];
  private int size;

  IndexShard(int hashShift) {
    this.hashShift = hashShift;
  }

  void incrementOrCreate(String term, int docId, int tf) {
    lock.writeLock().lock();
    try {
      int slot = findOrInsert(term, Fnv1a.hash32(term));
      values[slot].append(docId, tf);
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Appends {@code postings[order[i]]} under {@code terms[order[i]]} for every {@code i} in {@code
   * [from, to)}, holding the write lock once for the whole batch.
   */
  void mergeBatch(
      String[] terms, int[] hashes, Postings[] postings, int[] order, int from, int to) {
    lock.writeLock().lock();
    try {
      for (int i = from; i < to; i++) {
        int source = order[i];
        // Resolve the slot before reading `values`: findOrInsert may rehash and replace the array.
        int slot = findOrInsert(terms[source], hashes[source]);
        values[slot].appendAll(postings[source]);
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  /** Returns a private snapshot of the term's postings, or {@code null} if it is absent. */
  Postings lookup(String term) {
    return lookup(term, Fnv1a.hash32(term));
  }

  Postings lookup(String term, int hash) {
    lock.readLock().lock();
    try {
      int mask = keys.length - 1;
      for (int i = (hash >>> hashShift) & mask; keys[i] != null; i = (i + 1) & mask) {
        if (keys[i].equals(term)) {
          return values[i].copy();
        }
      }
      return null;
    } finally {
      lock.readLock().unlock();
    }
  }

  int size() {
    lock.readLock().lock();
    try {
      return size;
    } finally {
      lock.readLock().unlock();
    }
  }

  int capacity() {
    lock.readLock().lock();
    try {
      return keys.length;
    } finally {
      lock.readLock().unlock();
    }
  }

  private int findOrInsert(String term, int hash) {
    int i = probe(keys, term, hash);
    if (keys[i] != null) {
      return i;
    }
    if ((size + 1) * 10L > keys.length * 7L) {
      rehash(keys.length * 2);
      i = probe(keys, term, hash);
    }
    keys[i] = term;
    values[i] = new Postings();
    size++;
    return i;
  }

  /** Returns the slot holding {@code term}, or the empty slot where it would go. */
  private int probe(String[] table, String term, int hash) {
    int mask = table.length - 1;
    int i = (hash >>> hashShift) & mask;
    while (table[i] != null && !table[i].equals(term)) {
      i = (i + 1) & mask;
    }
    return i;
  }

  private void rehash(int newCapacity) {
    String[] newKeys = new String[newCapacity];
    Postings[] newValues = new Postings[newCapacity];
    for (int j = 0; j < keys.length; j++) {
      if (keys[j] != null) {
        int i = probe(newKeys, keys[j], Fnv1a.hash32(keys[j]));
        newKeys[i] = keys[j];
        newValues[i] = values[j];
      }
    }
    keys = newKeys;
    values = newValues;
  }
}
