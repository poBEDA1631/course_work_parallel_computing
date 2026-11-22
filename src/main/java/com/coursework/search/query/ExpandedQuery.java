package com.coursework.search.query;

import com.coursework.search.text.Stemmer;
import com.coursework.search.text.Tokenizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A user query plus expansion keywords, split into what BM25 needs (stemmed terms with weights) and
 * what the client sees (the expansion terms that add something to the query).
 */
public final class ExpandedQuery {
  public static final double ORIGINAL_WEIGHT = 1.0;
  public static final double EXPANSION_WEIGHT = 0.5;

  private final String[] words;
  private final String[] terms;
  private final double[] weights;
  private final List<String> newTerms;

  private ExpandedQuery(String[] words, String[] terms, double[] weights, List<String> newTerms) {
    this.words = words;
    this.terms = terms;
    this.weights = weights;
    this.newTerms = newTerms;
  }

  public static ExpandedQuery of(String query) {
    return of(query, List.of());
  }

  /**
   * Original words keep weight 1.0. Each expansion keyword is tokenized and stemmed like a
   * document; every resulting stem not already in the query gets {@link #EXPANSION_WEIGHT}. A
   * keyword is shown as new unless all its stems are already query stems.
   */
  public static ExpandedQuery of(String query, List<String> expansion) {
    List<String> words = tokens(query);
    Set<String> originalStems = new LinkedHashSet<>();
    for (String word : words) {
      originalStems.add(Stemmer.stem(word));
    }

    Set<String> expansionStems = new LinkedHashSet<>();
    List<String> newTerms = new ArrayList<>();
    for (String keyword : expansion) {
      boolean addsSomething = false;
      for (String token : tokens(keyword)) {
        String stem = Stemmer.stem(token);
        if (!originalStems.contains(stem)) {
          expansionStems.add(stem);
          addsSomething = true;
        }
      }
      if (addsSomething) {
        newTerms.add(keyword);
      }
    }

    int n = originalStems.size() + expansionStems.size();
    String[] terms = new String[n];
    double[] weights = new double[n];
    int i = 0;
    for (String stem : originalStems) {
      terms[i] = stem;
      weights[i++] = ORIGINAL_WEIGHT;
    }
    for (String stem : expansionStems) {
      terms[i] = stem;
      weights[i++] = EXPANSION_WEIGHT;
    }
    return new ExpandedQuery(words.toArray(new String[0]), terms, weights, List.copyOf(newTerms));
  }

  /** Expansion keywords that contribute at least one stem the query does not already have. */
  public List<String> newTerms() {
    return newTerms;
  }

  /** Lower-cased, unstemmed tokens of the original query; snippets look only for these. */
  String[] words() {
    return words.clone();
  }

  String[] terms() {
    return terms.clone();
  }

  double[] weights() {
    return weights.clone();
  }

  private static List<String> tokens(String text) {
    List<String> tokens = new ArrayList<>();
    Tokenizer.forEachToken(text, tokens::add);
    return tokens;
  }
}
