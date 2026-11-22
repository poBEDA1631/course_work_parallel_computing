package com.coursework.search.ollama;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Expands a query into related keywords through Ollama. Never fails: on timeout, an unreachable
 * server or an unusable answer it returns an empty list, so search falls back to the original
 * query.
 */
public final class QueryExpansionService {
  public static final int MAX_TERMS = 8;

  /** Longer "terms" are sentences (refusals, explanations), not keywords. */
  static final int MAX_TERM_WORDS = 3;

  static final int MAX_TERM_LENGTH = 40;

  private static final Logger LOG = LoggerFactory.getLogger(QueryExpansionService.class);

  private static final String PROMPT_PREFIX =
      """
      You expand short search queries into related keywords for a movie and music review search \
      engine. Respond with ONLY a comma-separated list of 3 to 8 related keywords, no explanation.

      Query: "scary movies"
      Keywords: horror, thriller, suspense, halloween, frightening, slasher

      Query: "guitar music"
      Keywords: rock, acoustic, strings, instrumental, band, blues

      Query: \"""";

  private static final String PROMPT_SUFFIX = "\"\nKeywords:";

  private final OllamaClient client;
  private final QueryExpansionCache cache;

  public QueryExpansionService(OllamaClient client, QueryExpansionCache cache) {
    this.client = client;
    this.cache = cache;
  }

  /**
   * Returns at most {@link #MAX_TERMS} lower-case keywords. Only answers that were received and
   * parsed are cached; failures are retried on the next call.
   */
  public List<String> expand(String query) {
    String key = query.trim().toLowerCase(Locale.ROOT);
    if (key.isEmpty()) {
      return List.of();
    }
    List<String> cached = cache.get(key);
    if (cached != null) {
      return cached;
    }
    String answer;
    try {
      answer = client.generate(prompt(query));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return List.of();
    } catch (IOException | RuntimeException e) {
      LOG.warn("Query expansion failed for \"{}\": {}", query, e.toString());
      return List.of();
    }
    List<String> terms = parse(answer);
    cache.put(key, terms);
    return terms;
  }

  static String prompt(String query) {
    return PROMPT_PREFIX + query + PROMPT_SUFFIX;
  }

  /**
   * Splits on commas, trims, lower-cases, drops empty and sentence-like entries, removes duplicates
   * keeping the first occurrence, and keeps at most {@link #MAX_TERMS}.
   */
  static List<String> parse(String answer) {
    Set<String> unique = new LinkedHashSet<>();
    for (String part : answer.split(",")) {
      String term = part.trim().toLowerCase(Locale.ROOT);
      if (term.isEmpty()
          || term.length() > MAX_TERM_LENGTH
          || term.split("\\s+").length > MAX_TERM_WORDS) {
        continue;
      }
      unique.add(term);
      if (unique.size() == MAX_TERMS) {
        break;
      }
    }
    return List.copyOf(new ArrayList<>(unique));
  }
}
