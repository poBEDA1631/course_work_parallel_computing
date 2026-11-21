package com.coursework.search.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class LocalAccumulatorTest {
  @Test
  void foldsRepeatedTermsWithinADocument() {
    LocalAccumulator acc = new LocalAccumulator();
    acc.add("a", 1);
    acc.add("b", 1);
    acc.add("a", 1);
    acc.add("a", 2);
    assertEquals(2, acc.size());
    Postings a = acc.get("a");
    assertEquals(2, a.size());
    assertEquals(1, a.docId(0));
    assertEquals(2, a.termFrequency(0));
    assertEquals(2, a.docId(1));
    assertEquals(1, a.termFrequency(1));
    assertEquals(1, acc.get("b").size());
    assertNull(acc.get("c"));
  }

  @Test
  void growsPastInitialCapacity() {
    LocalAccumulator acc = new LocalAccumulator();
    int terms = LocalAccumulator.INITIAL_CAPACITY * 5;
    for (int i = 0; i < terms; i++) {
      acc.add("w" + i, i);
      acc.add("w" + i, i);
    }
    assertEquals(terms, acc.size());
    for (int i = 0; i < terms; i++) {
      Postings p = acc.get("w" + i);
      assertEquals(i, p.docId(0));
      assertEquals(2, p.termFrequency(0));
    }
  }
}
