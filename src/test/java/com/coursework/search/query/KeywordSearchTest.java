package com.coursework.search.query;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

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

  private static TopKSelector.Hit hit(int docId, double score) {
    return new TopKSelector.Hit(docId, score);
  }
}
