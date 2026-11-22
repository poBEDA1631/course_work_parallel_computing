package com.coursework.search.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RebuildTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path dataset;
  private final HttpClient http = HttpClient.newHttpClient();
  private final AtomicBoolean pauseNextBuild = new AtomicBoolean();
  private final CountDownLatch paused = new CountDownLatch(1);
  private final CountDownLatch resume = new CountDownLatch(1);
  private IndexService service;
  private Javalin app;

  @BeforeEach
  void start() throws Exception {
    Files.writeString(dataset.resolve("KINDOFBLUE.txt"), "Kind of Blue\nJazz piano trio");
    Files.writeString(dataset.resolve("METAL.txt"), "Master of Puppets\nHeavy guitar riffs");
    service =
        new IndexService(
            dataset,
            () -> {
              if (pauseNextBuild.getAndSet(false)) {
                paused.countDown();
                awaitQuietly(resume);
              }
            });
    app = FakeOllama.offlineServer(service).start(0);
    service.rebuild(2);
  }

  @AfterEach
  void stop() {
    resume.countDown();
    app.stop();
  }

  @Test
  void oldIndexIsReleasedAndEveryEndpointReportsTheBuild() throws Exception {
    pauseNextBuild.set(true);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread rebuild =
        new Thread(
            () -> {
              try {
                service.rebuild(4);
              } catch (Throwable t) {
                failure.set(t);
              }
            });
    rebuild.start();
    assertTrue(paused.await(10, TimeUnit.SECONDS));

    assertTrue(service.current().isEmpty(), "old index still referenced during the rebuild");
    assertError(get("/api/stats"), 409, "index build in progress");
    assertError(
        post("/api/search", "{\"query\": \"jazz\", \"mode\": \"keyword\"}"),
        409,
        "index build in progress");
    assertError(post("/api/index", "{\"threads\": 2}"), 409, "index build already in progress");
    assertEquals(
        JSON.readTree("{\"stage\": \"error\", \"message\": \"index build in progress\"}"),
        JSON.readTree(wsAnswer("{\"query\": \"jazz\"}")));

    resume.countDown();
    rebuild.join();
    assertEquals(null, failure.get());
    JsonNode stats = JSON.readTree(get("/api/stats").body());
    assertEquals(2, stats.get("documents").asInt());
    assertEquals(4, stats.get("threads_used").asInt());
  }

  @Test
  void failedRebuildLeavesNoIndex() throws Exception {
    Files.delete(dataset.resolve("KINDOFBLUE.txt"));
    Files.delete(dataset.resolve("METAL.txt"));
    Files.delete(dataset);
    assertThrows(IOException.class, () -> service.rebuild(2));
    assertTrue(service.current().isEmpty());
    assertError(get("/api/stats"), 409, "index has not been built yet");
  }

  private static void assertError(HttpResponse<String> response, int status, String message)
      throws Exception {
    assertEquals(status, response.statusCode(), response.body());
    JsonNode body = JSON.readTree(response.body());
    assertEquals("error", body.get("status").asText());
    assertEquals(message, body.get("message").asText());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(uri("http", path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return http.send(
        HttpRequest.newBuilder(uri("http", path))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** Sends one message over a fresh connection and returns the first answer. */
  private String wsAnswer(String message) throws Exception {
    LinkedBlockingQueue<String> answers = new LinkedBlockingQueue<>();
    WebSocket socket =
        http.newWebSocketBuilder()
            .buildAsync(
                uri("ws", "/ws/search"),
                new WebSocket.Listener() {
                  private final StringBuilder partial = new StringBuilder();

                  @Override
                  public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                    partial.append(data);
                    if (last) {
                      answers.add(partial.toString());
                      partial.setLength(0);
                    }
                    ws.request(1);
                    return null;
                  }
                })
            .get(10, TimeUnit.SECONDS);
    try {
      socket.sendText(message, true).get(10, TimeUnit.SECONDS);
      String answer = answers.poll(10, TimeUnit.SECONDS);
      assertNotNull(answer, "no answer within 10 s");
      return answer;
    } finally {
      socket.abort();
    }
  }

  private URI uri(String scheme, String path) {
    return URI.create(scheme + "://localhost:" + app.port() + path);
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
