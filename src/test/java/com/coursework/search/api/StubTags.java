package com.coursework.search.api;

import com.coursework.search.ollama.OllamaClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

/** Serves {@code GET /api/tags} with a configurable model list, standing in for Ollama. */
final class StubTags implements AutoCloseable {
  volatile int status = 200;
  volatile String models = "[]";

  private final HttpServer server;

  StubTags() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/tags",
        exchange -> {
          byte[] body = ("{\"models\": " + models + "}").getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  OllamaClient client() {
    return new OllamaClient("http://127.0.0.1:" + server.getAddress().getPort(), "llama3.2:3b");
  }

  /** A client for a port nothing listens on. */
  static OllamaClient offline() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return new OllamaClient("http://127.0.0.1:" + socket.getLocalPort(), "llama3.2:3b");
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
