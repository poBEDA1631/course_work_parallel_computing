package com.coursework.search.api;

import com.coursework.search.ollama.OllamaClient;
import com.coursework.search.ollama.OllamaClients;
import com.coursework.search.ollama.QueryExpansionCache;
import com.coursework.search.ollama.QueryExpansionService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands in for Ollama in API tests: {@code GET /api/tags} with a configurable model list and
 * {@code POST /api/generate} with a configurable answer that can be held on a latch.
 */
final class FakeOllama implements AutoCloseable {
  private static final String MODEL = "llama3.2:3b";
  private static final Duration HELD_ANSWER_TIMEOUT = Duration.ofSeconds(60);

  volatile int status = 200;
  volatile String models = "[{\"name\": \"" + MODEL + "\"}]";
  volatile String answer = "";
  volatile boolean holdGenerate;
  final CountDownLatch release = new CountDownLatch(1);
  final AtomicInteger generateCalls = new AtomicInteger();

  private final HttpServer server;
  private final ExecutorService executor = Executors.newCachedThreadPool();

  FakeOllama() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/tags", exchange -> respond(exchange, "{\"models\": " + models + "}"));
    server.createContext(
        "/api/generate",
        exchange -> {
          generateCalls.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          if (holdGenerate) {
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          respond(exchange, "{\"response\": \"" + answer.replace("\"", "\\\"") + "\"}");
        });
    // Several handlers may be held at once; the default executor would serialize them.
    server.setExecutor(executor);
    server.start();
  }

  /** An unstarted server whose health check and query expansion talk to this fake. */
  Javalin server(IndexService indexService) {
    return serverFor(indexService, "http://127.0.0.1:" + server.getAddress().getPort());
  }

  /** An unstarted server whose Ollama URL points at a port nothing listens on. */
  static Javalin offlineServer(IndexService indexService) throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return serverFor(indexService, "http://127.0.0.1:" + socket.getLocalPort());
    }
  }

  @Override
  public void close() {
    release.countDown();
    server.stop(0);
    executor.shutdownNow();
  }

  private static Javalin serverFor(IndexService indexService, String url) {
    // Generous timeout: tests hold answers on a latch and must not race the real 5 s limit.
    OllamaClient client = OllamaClients.withGenerateTimeout(url, MODEL, HELD_ANSWER_TIMEOUT);
    return ApiServer.create(
        indexService, client, new QueryExpansionService(client, new QueryExpansionCache()));
  }

  private void respond(HttpExchange exchange, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    try {
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
    } catch (IOException e) {
      // The client gave up waiting.
    } finally {
      exchange.close();
    }
  }
}
