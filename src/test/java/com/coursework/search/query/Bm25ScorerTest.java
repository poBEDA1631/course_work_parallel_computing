package com.coursework.search.query;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coursework.search.index.InvertedIndex;
import com.coursework.search.index.LocalAccumulator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Corpus (already stemmed), N = 3, avgdl = (3 + 2 + 1) / 3 = 2:
 *
 * <pre>
 *   doc 0: space movie space   dl = 3
 *   doc 1: movie night         dl = 2
 *   doc 2: space               dl = 1
 * </pre>
 */
class Bm25ScorerTest {
  private static final double EPS = 1e-12;
  private static final int[] DOC_LENGTHS = {3, 2, 1};

  private Bm25Scorer scorer;

  @BeforeEach
  void buildIndex() {
    LocalAccumulator acc = new LocalAccumulator();
    String[][] docs = {{"space", "movie", "space"}, {"movie", "night"}, {"space"}};
    for (int doc = 0; doc < docs.length; doc++) {
      for (String term : docs[doc]) {
        acc.add(term, doc);
      }
    }
    InvertedIndex index = new InvertedIndex(4);
    index.mergeFrom(acc);
    scorer = new Bm25Scorer(index, DOC_LENGTHS, 2.0, 3);
  }

  @Test
  void idfMatchesHandCalculation() {
    // df = 2: ln((3 - 2 + 0.5) / (2 + 0.5) + 1) = ln(1.5 / 2.5 + 1) = ln(1.6)
    assertEquals(0.47000362924573563, Bm25Scorer.idf(3, 2), EPS);
    // df = N stays positive: ln(0.5 / 3.5 + 1) = ln(8 / 7)
    assertEquals(0.13353139262452257, Bm25Scorer.idf(3, 3), EPS);
  }

  @Test
  void singleTermMatchesHandCalculation() {
    // idf(space) = ln(1.6). Contribution = idf * tf * (k1 + 1) / (tf + k1 * (1 - b + b * dl / 2)).
    // doc 0: tf 2, dl 3 -> 1 - 0.75 + 0.75 * 1.5 = 1.375; 2 * 2.2 / (2 + 1.65)  = 4.4 / 3.65
    // doc 2: tf 1, dl 1 -> 1 - 0.75 + 0.75 * 0.5 = 0.625; 1 * 2.2 / (1 + 0.75)  = 2.2 / 1.75
    double[] scores = scorer.score(new String[] {"space"});
    assertArrayEquals(new double[] {0.5665797174469143, 0, 0.5908617053374963}, scores, EPS);
    assertEquals(Math.log(1.6) * 4.4 / 3.65, scores[0], EPS);
    assertEquals(Math.log(1.6) * 2.2 / 1.75, scores[2], EPS);
  }

  @Test
  void termContributionsAreSummed() {
    // movie: idf ln(1.6); doc 0: 2.2 / (1 + 1.65) = 2.2 / 2.65; doc 1: dl = avgdl -> 2.2 / 2.2 = 1
    double[] scores = scorer.score(new String[] {"space", "movie"});
    assertEquals(0.5665797174469143 + 0.39019169220400696, scores[0], EPS);
    assertEquals(0.47000362924573563, scores[1], EPS);
    assertEquals(0.5908617053374963, scores[2], EPS);
  }

  @Test
  void repeatedQueryTermCountsOnce() {
    assertArrayEquals(
        scorer.score(new String[] {"space"}),
        scorer.score(new String[] {"space", "space", "space"}),
        EPS);
  }

  @Test
  void unknownTermsLeaveEveryScoreAtZero() {
    assertArrayEquals(new double[3], scorer.score(new String[] {"galaxy", "nebula"}), 0);
    assertArrayEquals(new double[3], scorer.score(new String[0]), 0);
  }

  @Test
  void scoreArrayCoversSkippedDocIdsButIdfUsesIndexedCount() {
    // Doc id 3 belongs to a skipped file: length 0, no postings, still a valid slot.
    LocalAccumulator acc = new LocalAccumulator();
    acc.add("space", 0);
    acc.add("space", 4);
    InvertedIndex index = new InvertedIndex(1);
    index.mergeFrom(acc);
    double[] scores =
        new Bm25Scorer(index, new int[] {1, 1, 1, 0, 1}, 1.0, 4).score(new String[] {"space"});
    assertEquals(5, scores.length);
    // N = 4 indexed documents, df = 2: ln(2.5 / 2.5 + 1) = ln 2; dl = avgdl -> 2.2 / 2.2.
    assertEquals(Math.log(2), scores[4], EPS);
    assertEquals(0, scores[3]);
    assertTrue(scores[0] > 0);
  }
}
