package com.coursework.search.ollama;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-process stand-in for an Ollama server. Responses are configured per test; a handler can be
 * held on {@link #hold} to provoke client timeouts without sleeping. Warm-ups (requests with {@code
 * num_predict}) are counted and configured apart from expansions, because failed expansions start
 * them in the background.
 */
final class StubOllama implements AutoCloseable {
  final AtomicInteger generateCalls = new AtomicInteger();
  final AtomicReference<String> lastGenerateBody = new AtomicReference<>();
  final CountDownLatch hold = new CountDownLatch(1);
  final AtomicInteger warmUpCalls = new AtomicInteger();
  final AtomicReference<String> lastWarmUpBody = new AtomicReference<>();
  final CountDownLatch holdWarmUps = new CountDownLatch(1);

  volatile int generateStatus = 200;
  volatile String generateBody = "{\"response\": \"\"}";
  volatile boolean holdGenerate;
  volatile int tagsStatus = 200;
  volatile String tagsBody = "{\"models\": []}";
  volatile boolean holdTags;
  volatile boolean holdWarmUp;
  volatile int warmUpStatus = 200;

  /** Simulated model latency for every generate request (expansion and warm-up). */
  volatile long generateDelayMillis;

  private final HttpServer server;
  private final ExecutorService executor = Executors.newCachedThreadPool();

  StubOllama() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/generate",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          delay();
          if (body.contains("\"num_predict\"")) {
            warmUpCalls.incrementAndGet();
            lastWarmUpBody.set(body);
            if (holdWarmUp) {
              await(holdWarmUps);
            }
            respond(exchange, warmUpStatus, "{\"response\": \"x\"}");
            return;
          }
          generateCalls.incrementAndGet();
          lastGenerateBody.set(body);
          if (holdGenerate) {
            awaitHold();
          }
          respond(exchange, generateStatus, generateBody);
        });
    server.createContext(
        "/api/tags",
        exchange -> {
          if (holdTags) {
            awaitHold();
          }
          respond(exchange, tagsStatus, tagsBody);
        });
    // A held warm-up must not stall other requests, as the default executor would.
    server.setExecutor(executor);
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  void respondWithText(String text) {
    generateBody = "{\"model\": \"m\", \"response\": " + quote(text) + ", \"done\": true}";
  }

  @Override
  public void close() {
    hold.countDown();
    holdWarmUps.countDown();
    server.stop(0);
    executor.shutdownNow();
  }

  private void awaitHold() {
    await(hold);
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Server-side latency of a slow model, not test synchronization. */
  private void delay() {
    long millis = generateDelayMillis;
    if (millis > 0) {
      try {
        Thread.sleep(millis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    try {
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
    } catch (IOException e) {
      // The client may already have given up (timeout tests).
    } finally {
      exchange.close();
    }
  }

  private static String quote(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }
}
