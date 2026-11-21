package com.coursework.search.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StemmerTest {
  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "parties, party",
    "boxes, box",
    "glasses, glass",
    "jumped, jump",
    "quickly, quick",
    "cats, cat",
    "songs, song",
    "1990s, 1990",
  })
  void appliesEachRule(String word, String expected) {
    assertEquals(expected, Stemmer.stem(word));
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "movies, movy",
    "running, runn",
    "stopped, stopp",
    "this, thi",
    "series, sery",
  })
  void keepsDocumentedInaccuracies(String word, String expected) {
    assertEquals(expected, Stemmer.stem(word));
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "uses, use",
    "ties, tie",
    "goes, goe",
    "lies, lie",
  })
  void skipsRuleThatWouldLeaveTooShortAStem(String word, String expected) {
    assertEquals(expected, Stemmer.stem(word));
  }

  @ParameterizedTest(name = "{0} unchanged")
  @CsvSource({"glass", "bus", "was", "is", "bed", "sing", "fly", "ring", "movie", "a", "''"})
  void leavesWordUnchanged(String word) {
    assertEquals(word, Stemmer.stem(word));
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "studies, study",
    "watches, watch",
    "singing, sing",
    "dvds, dvd",
  })
  void firstMatchingRuleWins(String word, String expected) {
    assertEquals(expected, Stemmer.stem(word));
  }
}
