package com.coursework.search.api;

import com.coursework.search.query.KeywordSearch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ApiServer {
  public static final int MAX_THREADS = 256;

  private static final Logger LOG = LoggerFactory.getLogger(ApiServer.class);

  record HealthResponse(String status, boolean ollama) {}

  record IndexResponse(String status, int documentsIndexed, long timeMs) {}

  record StatsResponse(int documents, long indexBuildTimeMs, int threadsUsed) {}

  record SearchResponse(List<KeywordSearch.Result> results, long timeMs) {}

  record ErrorResponse(String status, String message) {}

  private ApiServer() {}

  public static Javalin create(IndexService indexService) {
    ObjectMapper mapper =
        new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    Javalin app =
        Javalin.create(
            config -> {
              config.jsonMapper(new JavalinJackson(mapper, false));
              config.staticFiles.add("/static", Location.CLASSPATH);
            });

    app.get("/api/health", ctx -> ctx.json(new HealthResponse("ok", false)));

    app.post(
        "/api/index",
        ctx -> {
          int threads = parseThreads(mapper, ctx);
          IndexService.BuiltIndex built = indexService.rebuild(threads);
          LOG.info(
              "Indexed {} documents ({} skipped) with {} threads in {} ms",
              built.result().documentsIndexed(),
              built.result().documentsSkipped(),
              threads,
              built.result().timeMs());
          ctx.json(
              new IndexResponse("ok", built.result().documentsIndexed(), built.result().timeMs()));
        });

    app.get(
        "/api/stats",
        ctx -> {
          IndexService.BuiltIndex built =
              indexService
                  .current()
                  .orElseThrow(() -> new ApiException(409, "index has not been built yet"));
          ctx.json(
              new StatsResponse(
                  built.result().documentsIndexed(), built.result().timeMs(), built.threads()));
        });

    app.post(
        "/api/search",
        ctx -> {
          String query = parseSearchQuery(mapper, ctx);
          // During a rebuild this is still the previous, complete index.
          IndexService.BuiltIndex built =
              indexService
                  .current()
                  .orElseThrow(() -> new ApiException(409, "index has not been built yet"));
          long start = System.nanoTime();
          List<KeywordSearch.Result> results =
              new KeywordSearch(
                      built.index(),
                      built.docIds(),
                      built.result().docLengths(),
                      built.result().averageDocLength(),
                      built.result().documentsIndexed(),
                      indexService.datasetDir())
                  .search(query);
          ctx.json(new SearchResponse(results, (System.nanoTime() - start) / 1_000_000));
        });

    app.exception(
        ApiException.class,
        (e, ctx) -> ctx.status(e.status()).json(new ErrorResponse("error", e.getMessage())));
    app.exception(
        Exception.class,
        (e, ctx) -> {
          LOG.error("Request {} {} failed", ctx.method(), ctx.path(), e);
          ctx.status(500).json(new ErrorResponse("error", "internal error: " + e));
        });
    return app;
  }

  /** Requires {@code {"threads": N}} with an integral N in [1, MAX_THREADS]. */
  private static int parseThreads(ObjectMapper mapper, Context ctx) {
    JsonNode threads = readBody(mapper, ctx).get("threads");
    if (threads == null || !threads.isIntegralNumber() || !threads.canConvertToInt()) {
      throw new ApiException(400, "\"threads\" must be an integer");
    }
    int value = threads.intValue();
    if (value < 1 || value > MAX_THREADS) {
      throw new ApiException(400, "\"threads\" must be between 1 and " + MAX_THREADS);
    }
    return value;
  }

  /** Requires {@code {"query": string, "mode": "keyword"}}. */
  private static String parseSearchQuery(ObjectMapper mapper, Context ctx) {
    JsonNode body = readBody(mapper, ctx);
    JsonNode query = body.get("query");
    if (query == null || !query.isTextual()) {
      throw new ApiException(400, "\"query\" must be a string");
    }
    JsonNode mode = body.get("mode");
    if (mode == null || !mode.isTextual() || !mode.asText().equals("keyword")) {
      throw new ApiException(400, "\"mode\" must be \"keyword\"");
    }
    return query.asText();
  }

  private static JsonNode readBody(ObjectMapper mapper, Context ctx) {
    JsonNode body;
    try {
      body = mapper.readTree(ctx.body());
    } catch (Exception e) {
      throw new ApiException(400, "request body must be JSON");
    }
    if (body == null || !body.isObject()) {
      throw new ApiException(400, "request body must be a JSON object");
    }
    return body;
  }
}
