package com.coursework.search.api;

import com.coursework.search.concurrent.ThreadPool;
import com.coursework.search.ollama.QueryExpansionService;
import com.coursework.search.query.ExpandedQuery;
import com.coursework.search.query.KeywordSearch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /ws/search}: one request at a time per connection, answered as {@code query_expansion},
 * {@code keyword_search}, {@code done}, or a single {@code error} instead. Requests run on a shared
 * {@link ThreadPool} so that the WebSocket thread stays free to read (and reject) further messages.
 */
final class SearchSocket {
  static final int WORKERS = 8;

  private static final Logger LOG = LoggerFactory.getLogger(SearchSocket.class);
  private static final String STATE = "search-session";

  record ExpansionData(String original, List<String> expandedTerms) {}

  record ExpansionStage(String stage, ExpansionData data) {}

  record SearchData(List<KeywordSearch.Result> results, long timeMs) {}

  record SearchStage(String stage, SearchData data) {}

  record DoneStage(String stage) {}

  record ErrorStage(String stage, String message) {}

  /** Per connection: the busy flag, and a lock because two threads may send on one session. */
  private static final class Session {
    final AtomicBoolean busy = new AtomicBoolean();
    final Object sendLock = new Object();
  }

  private final IndexService indexService;
  private final QueryExpansionService expansion;
  private final ObjectMapper mapper;
  private final ThreadPool pool = new ThreadPool(WORKERS);

  /** Accepted requests not yet finished; capped so that {@code pool.submit} never blocks. */
  private final AtomicInteger inFlight = new AtomicInteger();

  private final int maxInFlight = pool.workerCount() + pool.queueCapacity();

  SearchSocket(IndexService indexService, QueryExpansionService expansion, ObjectMapper mapper) {
    this.indexService = indexService;
    this.expansion = expansion;
    this.mapper = mapper;
  }

  void configure(WsConfig ws) {
    ws.onConnect(ctx -> ctx.attribute(STATE, new Session()));
    ws.onMessage(ctx -> onMessage(ctx, ctx.message()));
  }

  void shutdown() {
    pool.shutdown();
  }

  private void onMessage(WsContext ctx, String message) {
    Session session = ctx.attribute(STATE);
    if (!session.busy.compareAndSet(false, true)) {
      send(ctx, session, new ErrorStage("error", "request already in progress"));
      return;
    }
    String query = parseQuery(message);
    if (query == null) {
      finish(ctx, session, new ErrorStage("error", "message must be {\"query\": string}"));
      return;
    }
    IndexService.BuiltIndex built;
    try {
      built = indexService.requireCurrent();
    } catch (ApiException e) {
      finish(ctx, session, new ErrorStage("error", e.getMessage()));
      return;
    }
    if (inFlight.incrementAndGet() > maxInFlight) {
      inFlight.decrementAndGet();
      finish(ctx, session, new ErrorStage("error", "server busy, try again later"));
      return;
    }
    try {
      pool.submit(() -> run(ctx, session, query, built));
    } catch (IllegalStateException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      inFlight.decrementAndGet();
      finish(ctx, session, new ErrorStage("error", "server is shutting down"));
    }
  }

  private void run(WsContext ctx, Session session, String query, IndexService.BuiltIndex built) {
    try {
      List<String> expanded;
      try {
        expanded = expansion.expand(query);
      } catch (RuntimeException e) {
        LOG.warn("Query expansion failed for \"{}\"", query, e);
        expanded = List.of();
      }
      ExpandedQuery expandedQuery = ExpandedQuery.of(query, expanded);
      send(
          ctx,
          session,
          new ExpansionStage(
              "query_expansion", new ExpansionData(query, expandedQuery.newTerms())));

      long start = System.nanoTime();
      List<KeywordSearch.Result> results;
      try {
        results =
            new KeywordSearch(
                    built.index(),
                    built.docIds(),
                    built.result().docLengths(),
                    built.result().averageDocLength(),
                    built.result().documentsIndexed(),
                    indexService.datasetDir())
                .search(expandedQuery);
      } catch (RuntimeException e) {
        LOG.error("Keyword search failed for \"{}\"", query, e);
        finish(ctx, session, new ErrorStage("error", "keyword search failed"));
        return;
      }
      long timeMs = (System.nanoTime() - start) / 1_000_000;
      send(ctx, session, new SearchStage("keyword_search", new SearchData(results, timeMs)));
      finish(ctx, session, new DoneStage("done"));
    } catch (RuntimeException e) {
      LOG.error("Search request failed for \"{}\"", query, e);
      finish(ctx, session, new ErrorStage("error", "internal error"));
    } finally {
      // Not the busy flag: finish() already cleared it, and by now it may belong to the next
      // request on this connection.
      inFlight.decrementAndGet();
    }
  }

  /**
   * Sends the last message of a request. The flag is cleared first: a client may send its next
   * query as soon as it sees this message, and that query must not be rejected as concurrent.
   */
  private void finish(WsContext ctx, Session session, Object last) {
    session.busy.set(false);
    send(ctx, session, last);
  }

  private void send(WsContext ctx, Session session, Object message) {
    synchronized (session.sendLock) {
      try {
        ctx.send(mapper.writeValueAsString(message));
      } catch (Exception e) {
        // The client went away; nothing left to tell it.
        LOG.debug("Could not send to {}: {}", ctx.sessionId(), e.toString());
      }
    }
  }

  private String parseQuery(String message) {
    try {
      JsonNode query = mapper.readTree(message).get("query");
      return query != null && query.isTextual() ? query.asText() : null;
    } catch (Exception e) {
      return null;
    }
  }
}
