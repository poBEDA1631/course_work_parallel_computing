package com.coursework.search.index;

/**
 * Per-worker term table filled without any locking; it must never be shared between threads. Uses
 * the same open addressing with linear probing as {@link IndexShard}.
 */
public final class LocalAccumulator {
  static final int INITIAL_CAPACITY = 1024;

  private String[] keys = new String[INITIAL_CAPACITY];
  private int[] hashes = new int[INITIAL_CAPACITY];
  private Postings[] postings = new Postings[INITIAL_CAPACITY];
  private int size;

  /**
   * Records one occurrence of {@code term} in {@code docId}. All tokens of a document must be added
   * before the next document starts, so that repeated terms fold into a single posting.
   */
  public void add(String term, int docId) {
    int hash = Fnv1a.hash32(term);
    int i = probe(keys, hashes, term, hash);
    if (keys[i] == null) {
      if ((size + 1) * 10L > keys.length * 7L) {
        rehash(keys.length * 2);
        i = probe(keys, hashes, term, hash);
      }
      keys[i] = term;
      hashes[i] = hash;
      postings[i] = new Postings();
      size++;
    }
    postings[i].addOccurrence(docId);
  }

  public int size() {
    return size;
  }

  /** Returns the accumulated postings of {@code term} (not a copy), or {@code null}. */
  Postings get(String term) {
    int i = probe(keys, hashes, term, Fnv1a.hash32(term));
    return postings[i];
  }

  String[] keys() {
    return keys;
  }

  int[] hashes() {
    return hashes;
  }

  Postings[] postings() {
    return postings;
  }

  private static int probe(String[] table, int[] tableHashes, String term, int hash) {
    int mask = table.length - 1;
    int i = hash & mask;
    while (table[i] != null && !(tableHashes[i] == hash && table[i].equals(term))) {
      i = (i + 1) & mask;
    }
    return i;
  }

  private void rehash(int newCapacity) {
    String[] newKeys = new String[newCapacity];
    int[] newHashes = new int[newCapacity];
    Postings[] newPostings = new Postings[newCapacity];
    for (int j = 0; j < keys.length; j++) {
      if (keys[j] != null) {
        int i = probe(newKeys, newHashes, keys[j], hashes[j]);
        newKeys[i] = keys[j];
        newHashes[i] = hashes[j];
        newPostings[i] = postings[j];
      }
    }
    keys = newKeys;
    hashes = newHashes;
    postings = newPostings;
  }
}
