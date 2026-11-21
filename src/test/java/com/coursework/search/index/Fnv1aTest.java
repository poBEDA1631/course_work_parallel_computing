package com.coursework.search.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Fnv1aTest {
  @Test
  void matchesPublishedTestVectors() {
    assertEquals(0x811C9DC5, Fnv1a.hash32(""));
    assertEquals(0xE40C292C, Fnv1a.hash32("a"));
    assertEquals(0xBF9CF968, Fnv1a.hash32("foobar"));
  }

  @Test
  void matchesByteWiseReferenceForNonAsciiInput() {
    String[] inputs = {
      "search", "naïve", "café", "日本語", "😀 emoji", "\uD800x", "x\uDC00", "tail\uD83D", "߿ࠀ"
    };
    for (String s : inputs) {
      assertEquals(reference(s), Fnv1a.hash32(s), s);
    }
  }

  private static int reference(String s) {
    int h = 0x811C9DC5;
    for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
      h ^= b & 0xFF;
      h *= 0x01000193;
    }
    return h;
  }
}
