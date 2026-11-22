package com.coursework.search.api;

import com.coursework.search.concurrent.ThreadPool;
import com.coursework.search.index.InvertedIndex;
import com.coursework.search.indexing.BuildResult;
import com.coursework.search.indexing.DocumentLoader;
import com.coursework.search.indexing.IndexBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the current index and makes sure at most one build runs at a time. A rebuild releases the
 * current index first: two complete indexes do not fit in memory next to the loaded Ollama model,
 * so there is no index while a build runs.
 */
public final class IndexService {
  /**
   * @param docIds the document id of every doc id position, from the same file list the index was
   *     built from
   */
  public record BuiltIndex(InvertedIndex index, BuildResult result, int threads, String[] docIds) {}

  private final Path datasetDir;
  private final AtomicBoolean building = new AtomicBoolean();
  private final AtomicReference<BuiltIndex> current = new AtomicReference<>();
  private final Runnable afterRelease;

  public IndexService(Path datasetDir) {
    this(datasetDir, () -> {});
  }

  /** {@code afterRelease} runs once the old index is released; tests use it to pause a build. */
  IndexService(Path datasetDir, Runnable afterRelease) {
    this.datasetDir = datasetDir;
    this.afterRelease = afterRelease;
  }

  /**
   * Releases the current index, builds a fresh one and publishes it. If the build fails, no index
   * is left. Requests that already hold the old index finish on it.
   *
   * @throws ApiException 409 if another build is running
   */
  public BuiltIndex rebuild(int threads)
      throws IOException, InterruptedException, ExecutionException {
    if (!building.compareAndSet(false, true)) {
      throw new ApiException(409, "index build already in progress");
    }
    try {
      current.set(null);
      afterRelease.run();
      List<Path> files = DocumentLoader.listDocuments(datasetDir);
      ThreadPool pool = new ThreadPool(threads);
      try {
        InvertedIndex index = new InvertedIndex(IndexBuilder.shardCountFor(threads));
        BuildResult result = new IndexBuilder(pool, index).build(files);
        String[] docIds = new String[files.size()];
        for (int i = 0; i < docIds.length; i++) {
          docIds[i] = DocumentLoader.documentId(files.get(i));
        }
        BuiltIndex built = new BuiltIndex(index, result, threads, docIds);
        current.set(built);
        return built;
      } finally {
        pool.shutdown();
        // After a failed build, queued batches still drain; keep the build flag until they do.
        pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
      }
    } finally {
      building.set(false);
    }
  }

  public Path datasetDir() {
    return datasetDir;
  }

  public Optional<BuiltIndex> current() {
    return Optional.ofNullable(current.get());
  }

  /**
   * Returns the current index.
   *
   * @throws ApiException 409 while a build is running or if no index has been built yet
   */
  public BuiltIndex requireCurrent() {
    BuiltIndex built = current.get();
    if (built != null) {
      return built;
    }
    throw new ApiException(
        409, building.get() ? "index build in progress" : "index has not been built yet");
  }
}
