package com.coursework.search.query;

/**
 * Keeps the best {@code k} (docId, score) pairs in a bounded binary min-heap whose root is the
 * worst kept pair, so each offer costs O(log k). Higher score wins; equal scores prefer the lower
 * doc id, which makes the result independent of offer order.
 */
public final class TopKSelector {
  public record Hit(int docId, double score) {}

  private final int[] docIds;
  private final double[] scores;
  private int size;

  public TopKSelector(int k) {
    if (k < 1) {
      throw new IllegalArgumentException("k must be >= 1: " + k);
    }
    docIds = new int[k];
    scores = new double[k];
  }

  public void offer(int docId, double score) {
    if (size < docIds.length) {
      docIds[size] = docId;
      scores[size] = score;
      siftUp(docIds, scores, size++);
    } else if (better(docId, score, docIds[0], scores[0])) {
      docIds[0] = docId;
      scores[0] = score;
      siftDown(docIds, scores, size, 0);
    }
  }

  public int size() {
    return size;
  }

  /** Returns the kept pairs, best first, without changing the selector. */
  public Hit[] sortedDescending() {
    int[] heapDocs = docIds.clone();
    double[] heapScores = scores.clone();
    Hit[] hits = new Hit[size];
    for (int n = size; n > 0; n--) {
      hits[n - 1] = new Hit(heapDocs[0], heapScores[0]);
      heapDocs[0] = heapDocs[n - 1];
      heapScores[0] = heapScores[n - 1];
      siftDown(heapDocs, heapScores, n - 1, 0);
    }
    return hits;
  }

  private static boolean better(int docA, double scoreA, int docB, double scoreB) {
    return scoreA > scoreB || (scoreA == scoreB && docA < docB);
  }

  private static void siftUp(int[] docs, double[] scores, int i) {
    while (i > 0) {
      int parent = (i - 1) / 2;
      if (!better(docs[parent], scores[parent], docs[i], scores[i])) {
        return;
      }
      swap(docs, scores, i, parent);
      i = parent;
    }
  }

  private static void siftDown(int[] docs, double[] scores, int size, int i) {
    while (true) {
      int worst = i;
      int left = 2 * i + 1;
      int right = left + 1;
      if (left < size && better(docs[worst], scores[worst], docs[left], scores[left])) {
        worst = left;
      }
      if (right < size && better(docs[worst], scores[worst], docs[right], scores[right])) {
        worst = right;
      }
      if (worst == i) {
        return;
      }
      swap(docs, scores, i, worst);
      i = worst;
    }
  }

  private static void swap(int[] docs, double[] scores, int a, int b) {
    int doc = docs[a];
    docs[a] = docs[b];
    docs[b] = doc;
    double score = scores[a];
    scores[a] = scores[b];
    scores[b] = score;
  }
}
