package com.coursework.search.index;

/**
 * Parallel {@code docId}/{@code tf} arrays that grow by doubling. Not thread-safe: shared instances
 * are only touched under their shard's lock, and readers receive a private {@link #copy()}.
 */
public final class Postings {
  static final int INITIAL_CAPACITY = 4;

  private int[] docIds;
  private int[] termFrequencies;
  private int size;

  Postings() {
    this(INITIAL_CAPACITY);
  }

  private Postings(int capacity) {
    docIds = new int[capacity];
    termFrequencies = new int[capacity];
  }

  public int size() {
    return size;
  }

  public int docId(int i) {
    checkIndex(i);
    return docIds[i];
  }

  public int termFrequency(int i) {
    checkIndex(i);
    return termFrequencies[i];
  }

  void append(int docId, int tf) {
    ensureCapacity(size + 1);
    docIds[size] = docId;
    termFrequencies[size] = tf;
    size++;
  }

  void appendAll(Postings other) {
    ensureCapacity(size + other.size);
    System.arraycopy(other.docIds, 0, docIds, size, other.size);
    System.arraycopy(other.termFrequencies, 0, termFrequencies, size, other.size);
    size += other.size;
  }

  /** Bumps the tf of the last posting if it belongs to {@code docId}, else appends tf 1. */
  void addOccurrence(int docId) {
    if (size > 0 && docIds[size - 1] == docId) {
      termFrequencies[size - 1]++;
    } else {
      append(docId, 1);
    }
  }

  Postings copy() {
    Postings copy = new Postings(Math.max(size, 1));
    System.arraycopy(docIds, 0, copy.docIds, 0, size);
    System.arraycopy(termFrequencies, 0, copy.termFrequencies, 0, size);
    copy.size = size;
    return copy;
  }

  int capacity() {
    return docIds.length;
  }

  private void ensureCapacity(int required) {
    if (required <= docIds.length) {
      return;
    }
    int capacity = docIds.length;
    while (capacity < required) {
      capacity *= 2;
    }
    int[] newDocIds = new int[capacity];
    int[] newTermFrequencies = new int[capacity];
    System.arraycopy(docIds, 0, newDocIds, 0, size);
    System.arraycopy(termFrequencies, 0, newTermFrequencies, 0, size);
    docIds = newDocIds;
    termFrequencies = newTermFrequencies;
  }

  private void checkIndex(int i) {
    if (i < 0 || i >= size) {
      throw new IndexOutOfBoundsException("index " + i + ", size " + size);
    }
  }
}
