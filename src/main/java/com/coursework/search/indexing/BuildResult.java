package com.coursework.search.indexing;

/**
 * @param docLengths token count of every document after stemming, indexed by doc id; skipped
 *     documents have length 0
 * @param averageDocLength mean length over indexed documents only, 0 if there are none
 */
public record BuildResult(
    int documentsIndexed,
    int documentsSkipped,
    long timeMs,
    int[] docLengths,
    double averageDocLength) {}
