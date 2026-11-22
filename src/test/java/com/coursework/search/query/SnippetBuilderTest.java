package com.coursework.search.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SnippetBuilderTest {
  /** "w000 w001 ... w099": word i starts at 5 * i, so window edges are easy to predict. */
  private static final String WORDS = words(0, 100);

  @Test
  void windowAroundMatchIsTrimmedToWholeWords() {
    // "w025" at 125; window [25, 225). Char 24 is a space, so the start stays at word 5; char 225
    // starts word 45, so the end moves back to the space after word 44.
    assertEquals(words(5, 45), SnippetBuilder.build(WORDS, new String[] {"w025"}));
  }

  @Test
  void windowEdgeInsideAWordMovesInward() {
    // "025" matches inside "w025" at 126; window [26, 226). 26 is inside word 5, so the start
    // moves past it to word 6; 226 is inside word 45, so the end moves back to after word 44.
    assertEquals(words(6, 45), SnippetBuilder.build(WORDS, new String[] {"025"}));
  }

  @Test
  void matchingIsCaseInsensitiveAndLiteral() {
    String text = "Docking at the SPACESHIP was the best scene";
    assertEquals(text, SnippetBuilder.build(text, new String[] {"spaceship"}));
    assertEquals(text, SnippetBuilder.build(text, new String[] {"space"}));
  }

  @Test
  void earliestOccurrenceOfAnyQueryWordWins() {
    String text = WORDS + " alpha" + " filler".repeat(40) + " zeta";
    String snippet = SnippetBuilder.build(text, new String[] {"zeta", "alpha"});
    assertTrue(snippet.contains("alpha"), snippet);
    assertTrue(!snippet.contains("zeta"), snippet);
  }

  @Test
  void windowIsClippedAtTheStartAndEndOfTheText() {
    assertEquals(words(0, 20), SnippetBuilder.build(WORDS, new String[] {"w000"}).substring(0, 99));
    assertTrue(SnippetBuilder.build(WORDS, new String[] {"w099"}).endsWith("w099"));
    assertTrue(SnippetBuilder.build(WORDS, new String[] {"w002"}).startsWith("w000"));
  }

  @Test
  void fallsBackToTheStartWhenNoWordOccursLiterally() {
    // Characters [0, 200) end exactly before word 40 (char 199 is a space).
    assertEquals(words(0, 40), SnippetBuilder.build(WORDS, new String[] {"movy"}));
    assertEquals(words(0, 40), SnippetBuilder.build(WORDS, new String[0]));
    String shifted = "xx" + WORDS;
    assertEquals("xx" + words(0, 39), SnippetBuilder.build(shifted, new String[] {"absent"}));
  }

  @Test
  void shortTextIsReturnedWholeAndWhitespaceCollapsed() {
    String text = "The Matrix\nA hacker learns the truth\n\nFive Stars\nGreat movie!\n\n";
    assertEquals(
        "The Matrix A hacker learns the truth Five Stars Great movie!",
        SnippetBuilder.build(text, new String[] {"matrix"}));
    assertEquals("", SnippetBuilder.build("", new String[] {"x"}));
  }

  @Test
  void textWithoutSpacesIsCutAtTheWindowAnyway() {
    String text = "a".repeat(1000);
    String snippet = SnippetBuilder.build(text, new String[] {"zzz"});
    assertEquals(SnippetBuilder.FALLBACK_LENGTH, snippet.length());
  }

  @Test
  void lengthChangingLowerCaseDoesNotShiftTheMatch() {
    // 'İ'.toLowerCase() is two chars in String.toLowerCase; per-char lower-casing keeps one.
    String text = "İİİİ the space movie";
    assertEquals(text, SnippetBuilder.build(text, new String[] {"space"}));
  }

  private static String words(int from, int to) {
    StringBuilder sb = new StringBuilder();
    for (int i = from; i < to; i++) {
      if (sb.length() > 0) {
        sb.append(' ');
      }
      sb.append(String.format("w%03d", i));
    }
    return sb.toString();
  }
}
