package com.coursework.search.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class InvertedIndexTest {
  private static final int VOCABULARY = 1_000;
  private static final int MERGERS = 8;
  private static final int BATCHES_PER_MERGER = 5;
  private static final int DOCS_PER_BATCH = 80;
  private static final int DOCS = MERGERS * BATCHES_PER_MERGER * DOCS_PER_BATCH;

  /** Single-threaded reference: {@code EXPECTED[term][doc]} is the term frequency. */
  private static final int[][] EXPECTED = buildReference();

  @ParameterizedTest
  @ValueSource(ints = {0, -4, 3, 6, 12, 1000})
  void rejectsShardCountThatIsNotAPowerOfTwo(int shardCount) {
    assertThrows(IllegalArgumentException.class, () -> new InvertedIndex(shardCount));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 64, 1 << 10})
  void acceptsPowerOfTwoShardCount(int shardCount) {
    assertEquals(shardCount, new InvertedIndex(shardCount).shardCount());
  }

  @Test
  void shardForUsesLowHashBits() {
    InvertedIndex index = new InvertedIndex(16);
    for (int i = 0; i < 1000; i++) {
      String term = "t" + i;
      assertEquals(Fnv1a.hash32(term) & 15, index.shardFor(term));
    }
  }

  @Test
  void singleThreadedMergeMatchesReference() {
    InvertedIndex index = new InvertedIndex(8);
    for (int doc = 0; doc < DOCS; doc += DOCS_PER_BATCH) {
      index.mergeFrom(accumulate(doc, doc + DOCS_PER_BATCH));
    }
    assertMatchesReference(index);
    assertNull(index.search("missing"));
  }

  @Test
  void searchReturnsASnapshot() {
    InvertedIndex index = new InvertedIndex(4);
    LocalAccumulator first = new LocalAccumulator();
    first.add("x", 1);
    index.mergeFrom(first);
    Postings snapshot = index.search("x");
    LocalAccumulator second = new LocalAccumulator();
    second.add("x", 2);
    index.mergeFrom(second);
    assertEquals(1, snapshot.size());
    assertEquals(2, index.search("x").size());
  }

  @RepeatedTest(5)
  void concurrentMergesIntoOneShardMatchReference() throws Exception {
    InvertedIndex index = new InvertedIndex(1);
    runMergers(index, new AtomicBoolean());
    assertMatchesReference(index);
  }

  @RepeatedTest(5)
  void concurrentMergesIntoManyShardsMatchReference() throws Exception {
    InvertedIndex index = new InvertedIndex(32);
    runMergers(index, new AtomicBoolean());
    assertMatchesReference(index);
  }

  @RepeatedTest(5)
  void readersDuringMergesSeeOnlyConsistentSnapshots() throws Exception {
    readWhileMerging(new InvertedIndex(4), VOCABULARY);
  }

  /** Readers hammer the hottest terms of a single shard, where appends and rehashes are densest. */
  @RepeatedTest(5)
  void readersOfHotTermsInOneShardSeeOnlyConsistentSnapshots() throws Exception {
    readWhileMerging(new InvertedIndex(1), 16);
  }

  private static void readWhileMerging(InvertedIndex index, int termsToRead) throws Exception {
    AtomicBoolean done = new AtomicBoolean();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicLong postingsSeen = new AtomicLong();
    int readerCount = 4;
    Thread[] readers = new Thread[readerCount];
    for (int r = 0; r < readerCount; r++) {
      long seed = r;
      readers[r] =
          new Thread(
              () -> {
                try {
                  postingsSeen.addAndGet(readUntil(index, done, seed, termsToRead));
                } catch (Throwable t) {
                  failure.compareAndSet(null, t);
                }
              });
      readers[r].start();
    }
    try {
      runMergers(index, done);
    } finally {
      done.set(true);
      for (Thread reader : readers) {
        reader.join();
      }
    }
    if (failure.get() != null) {
      throw new AssertionError("reader failed", failure.get());
    }
    assertTrue(postingsSeen.get() > 0);
    assertMatchesReference(index);
  }

  /**
   * Checks every snapshot it reads: valid doc ids, the exact reference tf, no duplicate doc, and a
   * posting count that never shrinks between reads of the same term.
   */
  private static long readUntil(
      InvertedIndex index, AtomicBoolean done, long seed, int termsToRead) {
    SplittableRandom random = new SplittableRandom(seed);
    int[] lastSize = new int[VOCABULARY];
    int[] seenInSnapshot = new int[DOCS];
    int stamp = 0;
    long postings = 0;
    do {
      int term = random.nextInt(termsToRead);
      Postings p = index.search(termName(term));
      if (p == null) {
        assertEquals(0, lastSize[term], "term vanished");
        continue;
      }
      stamp++;
      assertTrue(p.size() >= lastSize[term], "postings shrank");
      lastSize[term] = p.size();
      for (int i = 0; i < p.size(); i++) {
        int doc = p.docId(i);
        assertTrue(doc >= 0 && doc < DOCS, "doc id out of range: " + doc);
        assertTrue(seenInSnapshot[doc] != stamp, "duplicate doc " + doc);
        seenInSnapshot[doc] = stamp;
        assertEquals(EXPECTED[term][doc], p.termFrequency(i));
      }
      postings += p.size();
    } while (!done.get());
    return postings;
  }

  /** Each merger owns a disjoint doc range and merges it in several batches. */
  private static void runMergers(InvertedIndex index, AtomicBoolean doneWhenFinished)
      throws InterruptedException {
    CountDownLatch start = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread[] threads = new Thread[MERGERS];
    for (int m = 0; m < MERGERS; m++) {
      int firstDoc = m * BATCHES_PER_MERGER * DOCS_PER_BATCH;
      threads[m] =
          new Thread(
              () -> {
                try {
                  start.await();
                  for (int b = 0; b < BATCHES_PER_MERGER; b++) {
                    int from = firstDoc + b * DOCS_PER_BATCH;
                    index.mergeFrom(accumulate(from, from + DOCS_PER_BATCH));
                  }
                } catch (Throwable t) {
                  failure.compareAndSet(null, t);
                }
              });
      threads[m].start();
    }
    start.countDown();
    for (Thread thread : threads) {
      thread.join();
    }
    doneWhenFinished.set(true);
    if (failure.get() != null) {
      throw new AssertionError("merger failed", failure.get());
    }
  }

  /** Every reference posting appears exactly once with its tf, and nothing else is present. */
  private static void assertMatchesReference(InvertedIndex index) {
    int expectedTerms = 0;
    for (int term = 0; term < VOCABULARY; term++) {
      int[] remaining = EXPECTED[term].clone();
      int expectedPostings = 0;
      for (int tf : remaining) {
        if (tf > 0) {
          expectedPostings++;
        }
      }
      Postings p = index.search(termName(term));
      if (expectedPostings == 0) {
        assertNull(p, termName(term));
        continue;
      }
      expectedTerms++;
      assertEquals(expectedPostings, p.size(), termName(term));
      for (int i = 0; i < p.size(); i++) {
        int doc = p.docId(i);
        assertTrue(
            remaining[doc] > 0, "unexpected or duplicate posting " + termName(term) + "@" + doc);
        assertEquals(remaining[doc], p.termFrequency(i));
        remaining[doc] = 0;
      }
    }
    assertEquals(expectedTerms, index.termCount());
  }

  private static LocalAccumulator accumulate(int fromDoc, int toDoc) {
    LocalAccumulator acc = new LocalAccumulator();
    for (int doc = fromDoc; doc < toDoc; doc++) {
      for (int term : tokensOf(doc)) {
        acc.add(termName(term), doc);
      }
    }
    return acc;
  }

  private static int[][] buildReference() {
    int[][] expected = new int[VOCABULARY][DOCS];
    for (int doc = 0; doc < DOCS; doc++) {
      for (int term : tokensOf(doc)) {
        expected[term][doc]++;
      }
    }
    return expected;
  }

  /** Skewed toward low ids so that popular terms are shared by many concurrent merges. */
  private static int[] tokensOf(int doc) {
    SplittableRandom random = new SplittableRandom(doc * 0x9E3779B97F4A7C15L);
    int[] tokens = new int[20 + random.nextInt(60)];
    for (int i = 0; i < tokens.length; i++) {
      double r = random.nextDouble();
      tokens[i] = (int) (VOCABULARY * r * r * r);
    }
    return tokens;
  }

  private static String termName(int term) {
    return "t" + term;
  }
}
