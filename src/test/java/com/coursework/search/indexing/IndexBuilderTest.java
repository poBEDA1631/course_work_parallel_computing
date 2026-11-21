package com.coursework.search.indexing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coursework.search.concurrent.ThreadPool;
import com.coursework.search.index.InvertedIndex;
import com.coursework.search.index.Postings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class IndexBuilderTest {
  private static final int DOCUMENTS = 300;
  private static final int VOCABULARY = 400;

  @TempDir Path dir;
  private List<Path> files;

  @BeforeEach
  void writeCorpus() throws IOException {
    for (int doc = 0; doc < DOCUMENTS; doc++) {
      SplittableRandom random = new SplittableRandom(doc);
      StringBuilder text = new StringBuilder();
      int words = 10 + random.nextInt(200);
      for (int w = 0; w < words; w++) {
        // Mixed case and punctuation exercise the tokenizer; "word<n>" survives stemming as is.
        text.append(random.nextBoolean() ? "Word" : "word")
            .append(random.nextInt(VOCABULARY))
            .append(random.nextInt(4) == 0 ? ", " : " ");
      }
      Files.writeString(dir.resolve(String.format("doc%04d.txt", doc)), text);
    }
    files = DocumentLoader.listDocuments(dir);
  }

  @Test
  void indexesStemmedLowerCasedTokens() throws Exception {
    Files.writeString(dir.resolve("zz-extra.txt"), "Parties and MOVIES; parties!");
    files = DocumentLoader.listDocuments(dir);
    InvertedIndex index = build(files, 2);
    int extraDocId = files.size() - 1;

    Postings party = index.search("party");
    assertEquals(1, party.size());
    assertEquals(extraDocId, party.docId(0));
    assertEquals(2, party.termFrequency(0));
    assertEquals(extraDocId, index.search("movy").docId(0));
    assertNull(index.search("parties"));
    assertNull(index.search("Parties"));
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 3, 4, 8, 16})
  void multiThreadedBuildMatchesSingleThreadedReference(int threads) throws Exception {
    InvertedIndex reference = build(files, 1);
    InvertedIndex parallel = build(files, threads);
    assertEquals(reference.termCount(), parallel.termCount());
    for (int t = 0; t < VOCABULARY; t++) {
      String term = "word" + t;
      assertArrayEquals(
          sortedPairs(reference.search(term)), sortedPairs(parallel.search(term)), term);
    }
  }

  @Test
  void reportsDocumentCountAndTime() throws Exception {
    ThreadPool pool = new ThreadPool(4);
    try {
      InvertedIndex index = new InvertedIndex(IndexBuilder.shardCountFor(4));
      BuildResult result = new IndexBuilder(pool, index).build(files);
      assertEquals(DOCUMENTS, result.documentsIndexed());
      assertEquals(0, result.documentsSkipped());
      assertTrue(result.timeMs() >= 0);
    } finally {
      shutdown(pool);
    }
  }

  @Test
  void unreadableFileIsSkippedWithoutFailingTheBuild() throws Exception {
    Files.delete(files.get(10));
    ThreadPool pool = new ThreadPool(4);
    try {
      InvertedIndex index = new InvertedIndex(IndexBuilder.shardCountFor(4));
      BuildResult result = new IndexBuilder(pool, index).build(files);
      assertEquals(DOCUMENTS - 1, result.documentsIndexed());
      assertEquals(1, result.documentsSkipped());
      for (int t = 0; t < VOCABULARY; t++) {
        Postings p = index.search("word" + t);
        for (int i = 0; p != null && i < p.size(); i++) {
          assertTrue(p.docId(i) != 10, "skipped document was indexed");
        }
      }
    } finally {
      shutdown(pool);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5, 8, 9, 64})
  void shardCountIsSmallestPowerOfTwoAtLeastFourTimesThreads(int threads) {
    int shards = IndexBuilder.shardCountFor(threads);
    assertEquals(1, Integer.bitCount(shards));
    assertTrue(shards >= 4 * threads);
    assertTrue(shards / 2 < 4 * threads);
  }

  private static InvertedIndex build(List<Path> files, int threads) throws Exception {
    ThreadPool pool = new ThreadPool(threads);
    try {
      InvertedIndex index = new InvertedIndex(IndexBuilder.shardCountFor(threads));
      new IndexBuilder(pool, index).build(files);
      return index;
    } finally {
      shutdown(pool);
    }
  }

  private static void shutdown(ThreadPool pool) throws InterruptedException {
    pool.shutdown();
    assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
  }

  /** Postings order depends on merge interleaving, so compare (docId, tf) pairs sorted. */
  private static long[] sortedPairs(Postings p) {
    if (p == null) {
      return new long[0];
    }
    long[] pairs = new long[p.size()];
    for (int i = 0; i < p.size(); i++) {
      pairs[i] = ((long) p.docId(i) << 32) | p.termFrequency(i);
    }
    Arrays.sort(pairs);
    return pairs;
  }
}
