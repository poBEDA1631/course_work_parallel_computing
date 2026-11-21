package com.coursework.search.text;

/**
 * Light suffix stripper, deliberately not Porter. Rules are tried in order and the first one whose
 * suffix matches and leaves a stem of at least {@value #MIN_STEM} characters is applied; a rule
 * that would leave a shorter stem is skipped and the next one is tried.
 *
 * <p>Known, accepted inaccuracies: {@code movies -> movy} and doubled consonants are kept ({@code
 * running -> runn}).
 */
public final class Stemmer {
  private static final int MIN_STEM = 3;

  private Stemmer() {}

  public static String stem(String word) {
    int n = word.length();
    if (word.endsWith("ies") && n - 3 >= MIN_STEM) {
      return word.substring(0, n - 3) + "y";
    }
    if (word.endsWith("es") && n - 2 >= MIN_STEM) {
      return word.substring(0, n - 2);
    }
    if (word.endsWith("ing") && n - 3 >= MIN_STEM) {
      return word.substring(0, n - 3);
    }
    if (word.endsWith("ed") && n - 2 >= MIN_STEM) {
      return word.substring(0, n - 2);
    }
    if (word.endsWith("ly") && n - 2 >= MIN_STEM) {
      return word.substring(0, n - 2);
    }
    if (word.endsWith("s") && !word.endsWith("ss") && n - 1 >= MIN_STEM) {
      return word.substring(0, n - 1);
    }
    return word;
  }
}
