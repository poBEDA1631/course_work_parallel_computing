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

  /**
   * Returns the BM25 score of every doc id. Repeated terms count once. Every matching term adds a
   * strictly positive amount, so a score of 0 means the document matched no term.
   */
  public double[] score(String[] stemmedTerms) {
    double[] scores = new double[docLengths.length];
    for (int t = 0; t < stemmedTerms.length; t++) {
      if (seenBefore(stemmedTerms, t)) {
        continue;
      }
      Postings postings = index.search(stemmedTerms[t]);
      if (postings == null) {
        continue;
      }
      double idf = idf(documentCount, postings.size());
      for (int i = 0; i < postings.size(); i++) {
        int doc = postings.docId(i);
        int tf = postings.termFrequency(i);
        double lengthNorm = 1 - B + B * docLengths[doc] / averageDocLength;
        scores[doc] += idf * tf * (K1 + 1) / (tf + K1 * lengthNorm);
      }
    }
    return scores;
  }

  static double idf(int documentCount, int documentFrequency) {
    return Math.log((documentCount - documentFrequency + 0.5) / (documentFrequency + 0.5) + 1);
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
