package com.coursework.search.query;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Comparator;
import java.util.SplittableRandom;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class TopKSelectorTest {
  private static final Comparator<TopKSelector.Hit> BEST_FIRST =
      Comparator.comparingDouble(TopKSelector.Hit::score)
          .reversed()
          .thenComparingInt(TopKSelector.Hit::docId);

  @Test
  void fewerCandidatesThanKAreAllReturnedSorted() {
    TopKSelector top = new TopKSelector(10);
    top.offer(7, 0.5);
    top.offer(3, 2.0);
    top.offer(9, 1.0);
    assertArrayEquals(
        new TopKSelector.Hit[] {hit(3, 2.0), hit(9, 1.0), hit(7, 0.5)}, top.sortedDescending());
  }

  @Test
  void emptySelectorReturnsNothing() {
    assertEquals(0, new TopKSelector(10).sortedDescending().length);
  }

  @Test
  void keepsOnlyTheBestKAndIgnoresWorseOffersWhenFull() {
    TopKSelector top = new TopKSelector(3);
    double[] scores = {5, 1, 4, 2, 3, 0.5, 6};
    for (int doc = 0; doc < scores.length; doc++) {
      top.offer(doc, scores[doc]);
    }
    assertArrayEquals(
        new TopKSelector.Hit[] {hit(6, 6), hit(0, 5), hit(2, 4)}, top.sortedDescending());
    assertEquals(3, top.size());
  }

  @Test
  void equalScoresPreferLowerDocIdRegardlessOfOfferOrder() {
    TopKSelector forward = new TopKSelector(3);
    TopKSelector backward = new TopKSelector(3);
    for (int doc = 0; doc < 8; doc++) {
      forward.offer(doc, 1.0);
      backward.offer(7 - doc, 1.0);
    }
    TopKSelector.Hit[] expected = {hit(0, 1.0), hit(1, 1.0), hit(2, 1.0)};
    assertArrayEquals(expected, forward.sortedDescending());
    assertArrayEquals(expected, backward.sortedDescending());
  }

  @Test
  void sortedDescendingDoesNotConsumeTheHeap() {
    TopKSelector top = new TopKSelector(2);
    top.offer(1, 1.0);
    top.offer(2, 2.0);
    top.sortedDescending();
    top.offer(3, 3.0);
    assertArrayEquals(new TopKSelector.Hit[] {hit(3, 3.0), hit(2, 2.0)}, top.sortedDescending());
  }

  @RepeatedTest(20)
  void matchesFullSortOnRandomInputWithManyTies() {
    SplittableRandom random = new SplittableRandom();
    int n = 1 + random.nextInt(2000);
    int k = 1 + random.nextInt(15);
    TopKSelector top = new TopKSelector(k);
    TopKSelector.Hit[] all = new TopKSelector.Hit[n];
    for (int doc = 0; doc < n; doc++) {
      // Few distinct values, so ties are common and the doc id tie-break is exercised.
      all[doc] = hit(doc, random.nextInt(20) / 4.0);
    }
    for (TopKSelector.Hit h : shuffled(all, random)) {
      top.offer(h.docId(), h.score());
    }
    Arrays.sort(all, BEST_FIRST);
    assertArrayEquals(Arrays.copyOf(all, Math.min(k, n)), top.sortedDescending());
  }

  @Test
  void rejectsNonPositiveK() {
    assertThrows(IllegalArgumentException.class, () -> new TopKSelector(0));
  }

  private static TopKSelector.Hit hit(int docId, double score) {
    return new TopKSelector.Hit(docId, score);
  }

  private static TopKSelector.Hit[] shuffled(TopKSelector.Hit[] hits, SplittableRandom random) {
    TopKSelector.Hit[] copy = hits.clone();
    for (int i = copy.length - 1; i > 0; i--) {
      int j = random.nextInt(i + 1);
      TopKSelector.Hit tmp = copy[i];
      copy[i] = copy[j];
      copy[j] = tmp;
    }
    return copy;
  }
}
