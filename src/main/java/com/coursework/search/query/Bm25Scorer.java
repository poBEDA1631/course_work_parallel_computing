package com.coursework.search.query;

import com.coursework.search.index.InvertedIndex;
import com.coursework.search.index.Postings;

/**
 * Okapi BM25 with {@code k1 = 1.2}, {@code b = 0.75} and {@code idf = ln((N - df + 0.5) / (df +
 * 0.5) + 1)}, which stays positive even for terms present in every document.
 */
public final class Bm25Scorer {
  public static final double K1 = 1.2;
  public static final double B = 0.75;

  private final InvertedIndex index;
  private final int[] docLengths;
  private final double averageDocLength;
  private final int documentCount;

  /**
   * @param docLengths token length per doc id; its length is the doc id space, which includes
   *     skipped documents
   * @param documentCount N in the idf: the number of documents actually indexed
   */
  public Bm25Scorer(
      InvertedIndex index, int[] docLengths, double averageDocLength, int documentCount) {
    this.index = index;
    this.docLengths = docLengths;
    this.averageDocLength = averageDocLength;
    this.documentCount = documentCount;
  }

  /** Scores with every term at weight 1.0; see {@link #score(String[], double[])}. */
  public double[] score(String[] stemmedTerms) {
    double[] weights = new double[stemmedTerms.length];
    for (int i = 0; i < weights.length; i++) {
      weights[i] = 1.0;
    }
    return score(stemmedTerms, weights);
  }

  /**
   * Returns the BM25 score of every doc id, each term's contribution multiplied by its weight. A
   * term given more than once counts once, with its largest weight (an original query term at 1.0
   * beats the same term from an expansion), so repeats never add up. With positive weights, a score
   * of 0 means the document matched no term.
   */
  public double[] score(String[] stemmedTerms, double[] weights) {
    if (weights.length != stemmedTerms.length) {
      throw new IllegalArgumentException("one weight per term is required");
    }
    double[] scores = new double[docLengths.length];
    for (int t = 0; t < stemmedTerms.length; t++) {
      if (seenBefore(stemmedTerms, t)) {
        continue;
      }
      Postings postings = index.search(stemmedTerms[t]);
      if (postings == null) {
        continue;
      }
      double weight = largestWeight(stemmedTerms, weights, t);
      double idf = idf(documentCount, postings.size());
      for (int i = 0; i < postings.size(); i++) {
        int doc = postings.docId(i);
        int tf = postings.termFrequency(i);
        double lengthNorm = 1 - B + B * docLengths[doc] / averageDocLength;
        scores[doc] += weight * idf * tf * (K1 + 1) / (tf + K1 * lengthNorm);
      }
    }
    return scores;
  }

  static double idf(int documentCount, int documentFrequency) {
    return Math.log((documentCount - documentFrequency + 0.5) / (documentFrequency + 0.5) + 1);
  }

  /** The largest weight among all occurrences of {@code terms[first]}. */
  private static double largestWeight(String[] terms, double[] weights, int first) {
    double weight = weights[first];
    for (int i = first + 1; i < terms.length; i++) {
      if (terms[i].equals(terms[first])) {
        weight = Math.max(weight, weights[i]);
      }
    }
    return weight;
  }

  private static boolean seenBefore(String[] terms, int t) {
    for (int i = 0; i < t; i++) {
      if (terms[i].equals(terms[t])) {
        return true;
      }
    }
    return false;
  }
}
