package com.coursework.search.query;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExpandedQueryTest {
  @Test
  void withoutExpansionEveryQueryStemHasFullWeight() {
    ExpandedQuery q = ExpandedQuery.of("Good MOVIES about Space");
    assertArrayEquals(new String[] {"good", "movies", "about", "space"}, q.words());
    assertArrayEquals(new String[] {"good", "movy", "about", "space"}, q.terms());
    assertArrayEquals(new double[] {1, 1, 1, 1}, q.weights());
    assertEquals(List.of(), q.newTerms());
  }

  @Test
  void expansionTermsAlreadyInTheQueryAreHiddenAndNotReweighted() {
    ExpandedQuery q =
        ExpandedQuery.of(
            "jazz piano",
            List.of("jazz", "piano", "swing", "jazz piano", "piano solo", "improvisation"));
    assertEquals(List.of("swing", "piano solo", "improvisation"), q.newTerms());
    assertArrayEquals(new String[] {"jazz", "piano", "swing", "solo", "improvisation"}, q.terms());
    assertArrayEquals(new double[] {1, 1, 0.5, 0.5, 0.5}, q.weights());
  }

  @Test
  void comparisonIsByStemNotBySpelling() {
    // "Songs", "song" and "SONGS" all stem to "song"; "ballads" stems to "ballad", which is new.
    ExpandedQuery q = ExpandedQuery.of("sad songs", List.of("song", "SONGS", "ballads"));
    assertEquals(List.of("ballads"), q.newTerms());
    assertArrayEquals(new String[] {"sad", "song", "ballad"}, q.terms());
    assertArrayEquals(new double[] {1, 1, 0.5}, q.weights());
  }

  @Test
  void phraseContributesEachNewStemOnce() {
    ExpandedQuery q =
        ExpandedQuery.of("space movies", List.of("science fiction", "fiction", "outer space"));
    // Hiding compares with the query only: "fiction" repeats an earlier expansion keyword, not
    // the query, so it is still shown, but its stem is weighted once.
    assertEquals(List.of("science fiction", "fiction", "outer space"), q.newTerms());
    assertArrayEquals(new String[] {"space", "movy", "science", "fiction", "outer"}, q.terms());
    assertArrayEquals(new double[] {1, 1, 0.5, 0.5, 0.5}, q.weights());
  }

  @Test
  void keywordWithoutTokensAddsNothing() {
    ExpandedQuery q = ExpandedQuery.of("rock", List.of("--", "  "));
    assertEquals(List.of(), q.newTerms());
    assertArrayEquals(new String[] {"rock"}, q.terms());
  }

  @Test
  void repeatedQueryWordsCountOnce() {
    ExpandedQuery q = ExpandedQuery.of("space Space spaces");
    assertArrayEquals(new String[] {"space", "spac"}, q.terms());
    assertArrayEquals(new double[] {1, 1}, q.weights());
  }
}
