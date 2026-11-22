package com.coursework.search.query;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coursework.search.index.InvertedIndex;
import com.coursework.search.index.LocalAccumulator;
import com.coursework.search.text.Stemmer;
import com.coursework.search.text.Tokenizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeywordSearchTest {
  @Test
  void minMaxMapsBestToOneAndWorstToZero() {
    TopKSelector.Hit[] hits = {hit(0, 9.0), hit(1, 6.0), hit(2, 3.0)};
    assertArrayEquals(new double[] {1.0, 0.5, 0.0}, KeywordSearch.normalize(hits), 1e-12);
  }

  @Test
  void equalScoresAllNormalizeToOne() {
    assertArrayEquals(
        new double[] {1.0, 1.0},
        KeywordSearch.normalize(new TopKSelector.Hit[] {hit(0, 2), hit(1, 2)}),
        0);
    assertArrayEquals(
        new double[] {1.0}, KeywordSearch.normalize(new TopKSelector.Hit[] {hit(5, 0.3)}), 0);
    assertArrayEquals(new double[0], KeywordSearch.normalize(new TopKSelector.Hit[0]), 0);
  }

  @Test
  void expansionTermsBringInMoreDocumentsAtLowerRank(@TempDir Path dir) throws Exception {
    String[] texts = {"Kind of Blue\njazz piano trio", "Big band\nswing music", "rock guitar"};
    KeywordSearch search = searchOver(dir, texts);

    assertEquals(List.of("D0"), ids(search.search("jazz piano")));

    List<KeywordSearch.Result> expanded =
        search.search(ExpandedQuery.of("jazz piano", List.of("swing", "jazz")));
    assertEquals(List.of("D0", "D1"), ids(expanded));
    assertEquals(1.0, expanded.get(0).score());
    assertEquals(0.0, expanded.get(1).score());
    // Snippets look only for the user's words: D1 has neither, so it falls back to its start.
    assertEquals("Big band swing music", expanded.get(1).snippet());
    assertTrue(expanded.get(0).snippet().contains("jazz piano"));
  }

  private static KeywordSearch searchOver(Path dir, String[] texts) throws Exception {
    LocalAccumulator acc = new LocalAccumulator();
    int[] lengths = new int[texts.length];
    String[] docIds = new String[texts.length];
    long total = 0;
    for (int doc = 0; doc < texts.length; doc++) {
      docIds[doc] = "D" + doc;
      Files.writeString(dir.resolve(docIds[doc] + ".txt"), texts[doc]);
      int id = doc;
      int[] count = {0};
      Tokenizer.forEachToken(
          texts[doc],
          token -> {
            acc.add(Stemmer.stem(token), id);
            count[0]++;
          });
      lengths[doc] = count[0];
      total += count[0];
    }
    InvertedIndex index = new InvertedIndex(2);
    index.mergeFrom(acc);
    return new KeywordSearch(
        index, docIds, lengths, (double) total / texts.length, texts.length, dir);
  }

  private static List<String> ids(List<KeywordSearch.Result> results) {
    return results.stream().map(KeywordSearch.Result::docId).toList();
  }

  private static TopKSelector.Hit hit(int docId, double score) {
    return new TopKSelector.Hit(docId, score);
  }
}
