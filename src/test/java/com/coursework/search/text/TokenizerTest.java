package com.coursework.search.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TokenizerTest {
  @Test
  void splitsOnNonAlphanumericsAndLowerCases() {
    assertEquals(
        List.of("the", "matrix", "1999", "is", "a", "sci", "fi", "classic"),
        tokens("The Matrix (1999) is a sci-fi CLASSIC!"));
  }

  @Test
  void dropsEmptyTokensBetweenRunsOfSeparators() {
    assertEquals(List.of("a", "b", "c"), tokens("  ,,a...b--\t\nc!!  "));
    assertEquals(List.of(), tokens(""));
    assertEquals(List.of(), tokens(" .,;-- "));
  }

  @Test
  void treatsApostrophesAndHtmlAsSeparators() {
    assertEquals(List.of("don", "t", "stop", "br", "ok"), tokens("Don't stop<br/>OK"));
  }

  @Test
  void keepsUnicodeLettersAndDigits() {
    assertEquals(List.of("café", "naïve", "日本", "x2"), tokens("Café NAÏVE 日本 x2"));
  }

  @Test
  void splitsOnSymbolsOutsideTheBasicPlane() {
    assertEquals(List.of("great", "movie"), tokens("great😀movie"));
  }

  private static List<String> tokens(String text) {
    List<String> out = new ArrayList<>();
    Tokenizer.forEachToken(text, out::add);
    return out;
  }
}
