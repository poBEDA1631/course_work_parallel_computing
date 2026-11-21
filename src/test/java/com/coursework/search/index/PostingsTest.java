package com.coursework.search.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PostingsTest {
  @Test
  void growsByDoublingFromInitialCapacity() {
    Postings p = new Postings();
    assertEquals(Postings.INITIAL_CAPACITY, p.capacity());
    for (int i = 0; i < 4; i++) {
      p.append(i, i + 1);
    }
    assertEquals(4, p.capacity());
    p.append(4, 5);
    assertEquals(8, p.capacity());
    for (int i = 5; i < 100; i++) {
      p.append(i, i + 1);
    }
    assertEquals(128, p.capacity());
    assertEquals(100, p.size());
    for (int i = 0; i < 100; i++) {
      assertEquals(i, p.docId(i));
      assertEquals(i + 1, p.termFrequency(i));
    }
  }

  @Test
  void appendAllConcatenates() {
    Postings a = new Postings();
    a.append(1, 1);
    Postings b = new Postings();
    for (int i = 2; i < 12; i++) {
      b.append(i, i);
    }
    a.appendAll(b);
    assertEquals(11, a.size());
    assertEquals(16, a.capacity());
    for (int i = 0; i < 11; i++) {
      assertEquals(i + 1, a.docId(i));
    }
  }

  @Test
  void addOccurrenceFoldsRepeatsOfTheLastDocument() {
    Postings p = new Postings();
    p.addOccurrence(7);
    p.addOccurrence(7);
    p.addOccurrence(9);
    assertEquals(2, p.size());
    assertEquals(7, p.docId(0));
    assertEquals(2, p.termFrequency(0));
    assertEquals(9, p.docId(1));
    assertEquals(1, p.termFrequency(1));
  }

  @Test
  void copyIsIndependentOfLaterAppends() {
    Postings p = new Postings();
    p.append(1, 3);
    Postings copy = p.copy();
    p.append(2, 4);
    assertEquals(1, copy.size());
    assertEquals(3, copy.termFrequency(0));
    assertThrows(IndexOutOfBoundsException.class, () -> copy.docId(1));
    assertThrows(IndexOutOfBoundsException.class, () -> copy.termFrequency(-1));
  }
}
