package com.coursework.search.text;

import java.util.function.Consumer;

/** Splits text on every code point that is not a letter or digit and lower-cases the tokens. */
public final class Tokenizer {
  private Tokenizer() {}

  /** Calls {@code sink} for each non-empty token, in order of appearance. */
  public static void forEachToken(CharSequence text, Consumer<String> sink) {
    StringBuilder token = new StringBuilder();
    int n = text.length();
    int i = 0;
    while (i < n) {
      int cp = Character.codePointAt(text, i);
      i += Character.charCount(cp);
      if (Character.isLetterOrDigit(cp)) {
        token.appendCodePoint(Character.toLowerCase(cp));
      } else if (!token.isEmpty()) {
        sink.accept(token.toString());
        token.setLength(0);
      }
    }
    if (!token.isEmpty()) {
      sink.accept(token.toString());
    }
  }
}
