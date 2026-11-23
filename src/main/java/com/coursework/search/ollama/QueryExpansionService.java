package com.coursework.search.ollama;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
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

  /** Shares the whole prompt prefix with real queries, so warming up also fills Ollama's cache. */
  static final String WARM_UP_QUERY = "warm up";

  /**
   * Long enough for a cold model load, which takes well over the 5 s expansion timeout; Ollama
   * aborts a load whose client disconnects, so a shorter wait would never let it finish.
   */
  public static final Duration WARM_UP_TIMEOUT = Duration.ofSeconds(30);

  /** Minimum time between the starts of two recovery warm-ups, so a busy server is not flooded. */
  public static final Duration RECOVERY_INTERVAL = Duration.ofSeconds(30);

  /**
   * The shorter wait after a warm-up that could not connect at all: such an attempt cost Ollama
   * nothing, and a server that is still starting usually listens a few seconds later.
   */
  public static final Duration RECOVERY_RETRY_INTERVAL = Duration.ofSeconds(2);

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
  private final Duration warmUpTimeout;
  private final LongSupplier nanoClock;
  private final AtomicBoolean recovering = new AtomicBoolean();
  private final AtomicInteger recoveryStarts = new AtomicInteger();

  // Read and written only by the thread holding the recovering flag.
  private boolean recoveredBefore;
  private long lastRecoveryStart;
  private boolean lastRecoveryUnreachable;

  public QueryExpansionService(OllamaClient client, QueryExpansionCache cache) {
    this(client, cache, WARM_UP_TIMEOUT, System::nanoTime);
  }

  /** Tests pass a short warm-up timeout and a clock they control. */
  QueryExpansionService(
      OllamaClient client,
      QueryExpansionCache cache,
      Duration warmUpTimeout,
      LongSupplier nanoClock) {
    this.client = client;
    this.cache = cache;
    this.warmUpTimeout = warmUpTimeout;
    this.nanoClock = nanoClock;
  }

  /**
   * Returns at most {@link #MAX_TERMS} lower-case keywords. Only answers that were received and
   * parsed are cached; failures are retried on the next call. A failure also starts a background
   * warm-up (see {@link #startRecovery()}); this call never waits for it.
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
      startRecovery();
      return List.of();
    }
    List<String> terms = parse(answer);
    cache.put(key, terms);
    return terms;
  }

  /**
   * Runs the expansion prompt once for {@link #WARM_UP_QUERY}, generating a single token, and
   * caches nothing. After a memory-heavy phase such as an index build the host may have compressed
   * or swapped out the idle model weights; paging them back in here keeps that delay off the next
   * real query. Waits up to {@link #WARM_UP_TIMEOUT}, so a cold model load can finish. Never
   * throws: a failure is logged and ignored.
   *
   * @return whether Ollama answered
   */
  public boolean warmUp() {
    return runWarmUp() == null;
  }

  /** The warm-up itself; returns {@code null} on success, otherwise the (logged) failure. */
  private Exception runWarmUp() {
    long start = System.nanoTime();
    try {
      client.generate(prompt(WARM_UP_QUERY), 1, warmUpTimeout);
      LOG.info("Ollama warm-up took {} ms", (System.nanoTime() - start) / 1_000_000);
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return e;
    } catch (IOException | RuntimeException e) {
      LOG.warn(
          "Ollama warm-up failed after {} ms: {}",
          (System.nanoTime() - start) / 1_000_000,
          e.toString());
      return e;
    }
  }

  /**
   * After a failed expansion, warms the model up on a daemon thread. A restarted Ollama has the
   * model unloaded, and every expansion would abort the reload at its 5 s timeout; the warm-up's
   * longer wait lets the load finish. At most one runs at a time, and starts are at least {@link
   * #RECOVERY_INTERVAL} apart, or {@link #RECOVERY_RETRY_INTERVAL} after a warm-up that could not
   * connect.
   */
  private void startRecovery() {
    if (!recovering.compareAndSet(false, true)) {
      return;
    }
    long now = nanoClock.getAsLong();
    Duration interval = lastRecoveryUnreachable ? RECOVERY_RETRY_INTERVAL : RECOVERY_INTERVAL;
    if (recoveredBefore && now - lastRecoveryStart < interval.toNanos()) {
      recovering.set(false);
      return;
    }
    recoveredBefore = true;
    lastRecoveryStart = now;
    int number = recoveryStarts.incrementAndGet();
    Thread thread =
        new Thread(
            () -> {
              try {
                LOG.info("Ollama recovery warm-up #{} started", number);
                lastRecoveryUnreachable = runWarmUp() instanceof ConnectException;
              } finally {
                recovering.set(false);
              }
            },
            "ollama-recovery");
    thread.setDaemon(true);
    thread.start();
  }

  /** Whether a recovery warm-up is running; for tests. */
  boolean recoveryRunning() {
    return recovering.get();
  }

  /** Recovery warm-ups started so far; for tests. */
  int recoveryStarts() {
    return recoveryStarts.get();
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
