package com.coursework.search.index;

/** 32-bit FNV-1a over the UTF-8 encoding of a string. */
public final class Fnv1a {
  private static final int OFFSET_BASIS = 0x811C9DC5;
  private static final int PRIME = 0x01000193;
  private static final int REPLACEMENT = '?';

  private Fnv1a() {}

  /**
   * Encodes UTF-8 on the fly instead of calling {@code getBytes}, so hashing allocates nothing.
   * Unpaired surrogates hash as {@code '?'}, matching {@code String.getBytes(UTF_8)}.
   */
  public static int hash32(String s) {
    int h = OFFSET_BASIS;
    int n = s.length();
    for (int i = 0; i < n; i++) {
      char c = s.charAt(i);
      if (c < 0x80) {
        h = (h ^ c) * PRIME;
      } else if (c < 0x800) {
        h = (h ^ (0xC0 | (c >>> 6))) * PRIME;
        h = (h ^ (0x80 | (c & 0x3F))) * PRIME;
      } else if (Character.isSurrogate(c)) {
        if (Character.isHighSurrogate(c)
            && i + 1 < n
            && Character.isLowSurrogate(s.charAt(i + 1))) {
          int cp = Character.toCodePoint(c, s.charAt(++i));
          h = (h ^ (0xF0 | (cp >>> 18))) * PRIME;
          h = (h ^ (0x80 | ((cp >>> 12) & 0x3F))) * PRIME;
          h = (h ^ (0x80 | ((cp >>> 6) & 0x3F))) * PRIME;
          h = (h ^ (0x80 | (cp & 0x3F))) * PRIME;
        } else {
          h = (h ^ REPLACEMENT) * PRIME;
        }
      } else {
        h = (h ^ (0xE0 | (c >>> 12))) * PRIME;
        h = (h ^ (0x80 | ((c >>> 6) & 0x3F))) * PRIME;
        h = (h ^ (0x80 | (c & 0x3F))) * PRIME;
      }
    }
    return h;
  }
}
