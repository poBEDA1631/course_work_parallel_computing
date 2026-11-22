package com.coursework.search.query;

import com.coursework.search.index.InvertedIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** BM25 keyword search over one built index: score, keep the top {@link #K}, normalize, snip. */
public final class KeywordSearch {
  public static final int K = 10;

  private static final Logger LOG = LoggerFactory.getLogger(KeywordSearch.class);

  public record Result(String docId, double score, String snippet) {}

  private final Bm25Scorer scorer;
  private final String[] docIds;
  private final Path datasetDir;

  /**
   * @param docIds document id per doc id position; documents are read from {@code
   *     datasetDir/<docId>.txt} for snippets
   * @param documentCount the number of documents actually indexed
   */
  public KeywordSearch(
      InvertedIndex index,
      String[] docIds,
      int[] docLengths,
      double averageDocLength,
      int documentCount,
      Path datasetDir) {
    this.scorer = new Bm25Scorer(index, docLengths, averageDocLength, documentCount);
    this.docIds = docIds;
    this.datasetDir = datasetDir;
  }

  /** Returns at most {@link #K} results, best first; empty if no query term is indexed. */
  public List<Result> search(String query) {
    return search(ExpandedQuery.of(query));
  }

  /**
   * Like {@link #search(String)}, with expansion terms ranked at their lower weight. Snippets are
   * still centred on the user's own words.
   */
  public List<Result> search(ExpandedQuery query) {
    double[] scores = scorer.score(query.terms(), query.weights());
    TopKSelector top = new TopKSelector(K);
    for (int doc = 0; doc < scores.length; doc++) {
      // Every matching term contributes a positive amount, so 0 means "no match".
      if (scores[doc] > 0) {
        top.offer(doc, scores[doc]);
      }
    }
    TopKSelector.Hit[] hits = top.sortedDescending();

    double[] normalized = normalize(hits);
    String[] queryWords = query.words();
    List<Result> results = new ArrayList<>(hits.length);
    for (int i = 0; i < hits.length; i++) {
      String docId = docIds[hits[i].docId()];
      results.add(new Result(docId, normalized[i], snippet(docId, queryWords)));
    }
    return results;
  }

  /** Min-max over the returned hits: best 1.0, worst 0.0; all 1.0 if every score is equal. */
  static double[] normalize(TopKSelector.Hit[] hits) {
    double[] normalized = new double[hits.length];
    if (hits.length == 0) {
      return normalized;
    }
    double max = hits[0].score();
    double min = hits[hits.length - 1].score();
    for (int i = 0; i < hits.length; i++) {
      normalized[i] = max == min ? 1.0 : (hits[i].score() - min) / (max - min);
    }
    return normalized;
  }

  private String snippet(String docId, String[] queryWords) {
    Path file = datasetDir.resolve(docId + ".txt");
    try {
      String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
      return SnippetBuilder.build(text, queryWords);
    } catch (IOException e) {
      LOG.warn("No snippet for {}: {}", file, e.toString());
      return "";
    }
  }
}
