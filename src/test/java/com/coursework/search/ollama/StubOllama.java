package com.coursework.search.ollama;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-process stand-in for an Ollama server. Responses are configured per test; a handler can be
 * held on {@link #hold} to provoke client timeouts without sleeping.
 */
final class StubOllama implements AutoCloseable {
  final AtomicInteger generateCalls = new AtomicInteger();
  final AtomicReference<String> lastGenerateBody = new AtomicReference<>();
  final CountDownLatch hold = new CountDownLatch(1);

  volatile int generateStatus = 200;
  volatile String generateBody = "{\"response\": \"\"}";
  volatile boolean holdGenerate;
  volatile int tagsStatus = 200;
  volatile String tagsBody = "{\"models\": []}";
  volatile boolean holdTags;

  private final HttpServer server;

  StubOllama() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/generate",
        exchange -> {
          generateCalls.incrementAndGet();
          lastGenerateBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
    server.setExecutor(null);
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
    server.stop(0);
  }

  private void awaitHold() {
    try {
      hold.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
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
