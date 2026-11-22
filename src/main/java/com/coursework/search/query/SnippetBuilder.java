package com.coursework.search.query;

/**
 * Cuts a snippet around the first literal, case-insensitive occurrence of any query word. When no
 * word occurs literally (stemming matched another form), the start of the document is used.
 */
public final class SnippetBuilder {
  static final int WINDOW = 100;
  static final int FALLBACK_LENGTH = 200;

  private SnippetBuilder() {}

  /**
   * @param queryWords lower-cased, unstemmed query tokens
   */
  public static String build(String text, String[] queryWords) {
    String lower = lowerCasePreservingLength(text);
    int pos = -1;
    int length = 0;
    for (String word : queryWords) {
      int i = word.isEmpty() ? -1 : lower.indexOf(word);
      if (i >= 0 && (pos < 0 || i < pos)) {
        pos = i;
        length = word.length();
      }
    }
    if (pos < 0) {
      return collapseWhitespace(text.substring(0, cutEnd(text, 0, FALLBACK_LENGTH)));
    }
    int start = cutStart(text, pos - WINDOW, pos);
    int end = cutEnd(text, pos + length, Math.max(pos + WINDOW, pos + length));
    return collapseWhitespace(text.substring(start, end));
  }

  /** Moves {@code start} forward past a partial word, but never beyond {@code limit}. */
  private static int cutStart(String text, int start, int limit) {
    if (start <= 0) {
      return 0;
    }
    if (Character.isWhitespace(text.charAt(start - 1))) {
      return start;
    }
    for (int i = start; i < limit; i++) {
      if (Character.isWhitespace(text.charAt(i))) {
        return i + 1;
      }
    }
    return start;
  }

  /** Moves {@code end} back before a partial word, but never below {@code limit}. */
  private static int cutEnd(String text, int limit, int end) {
    if (end >= text.length()) {
      return text.length();
    }
    if (Character.isWhitespace(text.charAt(end))) {
      return end;
    }
    for (int i = end - 1; i >= limit; i--) {
      if (Character.isWhitespace(text.charAt(i))) {
        return i;
      }
    }
    return end;
  }

  /**
   * Lower-cases char by char so that indexes in the result match indexes in {@code text}; {@code
   * String.toLowerCase} may change the length (e.g. for U+0130).
   */
  private static String lowerCasePreservingLength(String text) {
    char[] chars = new char[text.length()];
    for (int i = 0; i < chars.length; i++) {
      chars[i] = Character.toLowerCase(text.charAt(i));
    }
    return new String(chars);
  }

  /** Documents separate title, description and reviews with newlines; show them as one line. */
  private static String collapseWhitespace(String s) {
    StringBuilder out = new StringBuilder(s.length());
    boolean pendingSpace = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.isWhitespace(c)) {
        pendingSpace = !out.isEmpty();
      } else {
        if (pendingSpace) {
          out.append(' ');
          pendingSpace = false;
        }
        out.append(c);
      }
    }
    return out.toString();
  }
}
