package com.coursework.search.indexing;

import com.coursework.search.concurrent.TaskHandle;
import com.coursework.search.concurrent.ThreadPool;
import com.coursework.search.index.InvertedIndex;
import com.coursework.search.index.LocalAccumulator;
import com.coursework.search.text.Stemmer;
import com.coursework.search.text.Tokenizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds an {@link InvertedIndex} with one pool task per batch of {@link DocumentLoader#BATCH_SIZE}
 * files. Each task fills its own {@link LocalAccumulator} and merges it exactly once.
 */
public final class IndexBuilder {
  private static final Logger LOG = LoggerFactory.getLogger(IndexBuilder.class);

  private final ThreadPool pool;
  private final InvertedIndex index;

  public IndexBuilder(ThreadPool pool, InvertedIndex index) {
    this.pool = pool;
    this.index = index;
  }

  /** The smallest power of two that is at least {@code 4 * threads}. */
  public static int shardCountFor(int threads) {
    int target = Math.max(1, threads * 4);
    int count = Integer.highestOneBit(target);
    return count == target ? count : count << 1;
  }

  /**
   * Doc id {@code i} is {@code files.get(i)}. A file that cannot be read is logged and skipped
   * without failing the build.
   *
   * @throws ExecutionException if a batch task fails for any other reason
   */
  public BuildResult build(List<Path> files) throws InterruptedException, ExecutionException {
    long start = System.nanoTime();
    List<List<Path>> batches = DocumentLoader.batches(files);
    // Each batch writes only its own [firstDocId, firstDocId + batch size) range, so tasks never
    // touch the same element; TaskHandle.get() makes those writes visible to this thread.
    int[] docLengths = new int[files.size()];
    @SuppressWarnings("unchecked")
    TaskHandle<Integer>[] handles = new TaskHandle[batches.size()];
    for (int b = 0; b < batches.size(); b++) {
      List<Path> batch = batches.get(b);
      int firstDocId = b * DocumentLoader.BATCH_SIZE;
      handles[b] = pool.submit(() -> indexBatch(batch, firstDocId, docLengths));
    }
    int skipped = 0;
    for (TaskHandle<Integer> handle : handles) {
      skipped += handle.get();
    }
    long timeMs = (System.nanoTime() - start) / 1_000_000;
    int indexed = files.size() - skipped;
    long totalLength = 0;
    for (int length : docLengths) {
      totalLength += length;
    }
    double averageDocLength = indexed == 0 ? 0 : (double) totalLength / indexed;
    return new BuildResult(indexed, skipped, timeMs, docLengths, averageDocLength);
  }

  /** Returns the number of skipped files. */
  private int indexBatch(List<Path> batch, int firstDocId, int[] docLengths) {
    LocalAccumulator local = new LocalAccumulator();
    int skipped = 0;
    for (int i = 0; i < batch.size(); i++) {
      Path file = batch.get(i);
      String text;
      try {
        // Lenient decoding: malformed bytes become U+FFFD instead of failing the document.
        text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
      } catch (IOException e) {
        LOG.warn("Skipping unreadable document {}: {}", file, e.toString());
        skipped++;
        continue;
      }
      int docId = firstDocId + i;
      int[] length = {0};
      Tokenizer.forEachToken(
          text,
          token -> {
            local.add(Stemmer.stem(token), docId);
            length[0]++;
          });
      docLengths[docId] = length[0];
    }
    index.mergeFrom(local);
    return skipped;
  }
}
