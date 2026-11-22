package com.coursework.search.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApiServerTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient client = HttpClient.newHttpClient();

  @TempDir Path dataset;
  private Javalin app;

  @BeforeEach
  void start() throws Exception {
    Files.writeString(dataset.resolve("B001.txt"), "The Matrix. Great movies!");
    Files.writeString(dataset.resolve("B002.txt"), "Kind of Blue, jazz record");
    Files.writeString(dataset.resolve("B003.txt"), "Another movie review");
    app = FakeOllama.offlineServer(new IndexService(dataset)).start(0);
  }

  @AfterEach
  void stop() {
    app.stop();
  }

  @Test
  void healthReportsOllamaFalseWhenItIsUnreachable() throws Exception {
    HttpResponse<String> response = get("/api/health");
    assertEquals(200, response.statusCode());
    assertEquals(
        JSON.readTree("{\"status\":\"ok\",\"ollama\":false}"), JSON.readTree(response.body()));
  }

  @Test
  void healthFollowsTheRealAvailabilityOnEveryCall() throws Exception {
    try (FakeOllama ollama = new FakeOllama()) {
      app.stop();
      app = ollama.server(new IndexService(dataset)).start(0);

      ollama.models = "[]";
      assertEquals(false, JSON.readTree(get("/api/health").body()).get("ollama").asBoolean());
      ollama.models = "[{\"name\": \"llama3.2:3b\"}]";
      assertEquals(true, JSON.readTree(get("/api/health").body()).get("ollama").asBoolean());
      ollama.status = 503;
      HttpResponse<String> response = get("/api/health");
      assertEquals(200, response.statusCode());
      assertEquals(false, JSON.readTree(response.body()).get("ollama").asBoolean());
    }
  }

  @Test
  void statsBeforeFirstBuildIsConflict() throws Exception {
    assertError(get("/api/stats"), 409);
  }

  @Test
  void indexThenStats() throws Exception {
    HttpResponse<String> response = post("/api/index", "{\"threads\": 2}");
    assertEquals(200, response.statusCode(), response.body());
    JsonNode body = JSON.readTree(response.body());
    assertEquals("ok", body.get("status").asText());
    assertEquals(3, body.get("documents_indexed").asInt());
    assertTrue(body.get("time_ms").isIntegralNumber());
    assertEquals(3, body.size());

    JsonNode stats = JSON.readTree(get("/api/stats").body());
    assertEquals(3, stats.get("documents").asInt());
    assertEquals(body.get("time_ms").asLong(), stats.get("index_build_time_ms").asLong());
    assertEquals(2, stats.get("threads_used").asInt());
    assertEquals(3, stats.size());
  }

  @Test
  void builtIndexKeepsDocIdsOfTheFilesItWasBuiltFrom() throws Exception {
    IndexService service = new IndexService(dataset);
    IndexService.BuiltIndex built = service.rebuild(2);
    Files.writeString(dataset.resolve("A000.txt"), "added after the build");
    assertArrayEquals(new String[] {"B001", "B002", "B003"}, built.docIds());
    assertArrayEquals(new String[] {"B001", "B002", "B003"}, service.current().get().docIds());
    assertEquals(3, built.result().docLengths().length);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "not json",
        "[]",
        "{}",
        "{\"threads\": 0}",
        "{\"threads\": -3}",
        "{\"threads\": 2.5}",
        "{\"threads\": \"4\"}",
        "{\"threads\": null}",
        "{\"threads\": 257}",
        "{\"threads\": 99999999999}"
      })
  void invalidIndexRequestIsBadRequest(String body) throws Exception {
    assertError(post("/api/index", body), 400);
  }

  @Test
  void missingDatasetDirectoryIsServerError() throws Exception {
    app.stop();
    app = FakeOllama.offlineServer(new IndexService(dataset.resolve("missing"))).start(0);
    assertError(post("/api/index", "{\"threads\": 1}"), 500);
  }

  @Test
  void unknownRouteIsStillNotFound() throws Exception {
    assertEquals(404, get("/api/nope").statusCode());
  }

  private static void assertError(HttpResponse<String> response, int status) throws Exception {
    assertEquals(status, response.statusCode(), response.body());
    JsonNode body = JSON.readTree(response.body());
    assertEquals("error", body.get("status").asText());
    assertTrue(body.get("message").isTextual());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + app.port() + path);
  }
}
