package com.coursework.search.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SearchSocketTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path dataset;
  private final HttpClient http = HttpClient.newHttpClient();
  private FakeOllama ollama;
  private Javalin app;
  private Client client;

  @BeforeEach
  void start() throws Exception {
    Files.writeString(dataset.resolve("KINDOFBLUE.txt"), "Kind of Blue\nJazz piano trio record");
    Files.writeString(dataset.resolve("BIGBAND.txt"), "Big Band Hits\nSwing music to dance to");
    Files.writeString(dataset.resolve("METAL.txt"), "Master of Puppets\nHeavy guitar riffs");
    ollama = new FakeOllama();
    app = ollama.server(new IndexService(dataset)).start(0);
    client = connect();
  }

  @AfterEach
  void stop() {
    client.close();
    app.stop();
    ollama.close();
  }

  @Test
  void stagesFollowTheContract() throws Exception {
    build();
    ollama.answer = "Jazz, swing, big band, piano";
    client.send("{\"query\": \"jazz piano\"}");

    JsonNode expansion = client.next();
    assertEquals("query_expansion", expansion.get("stage").asText());
    assertEquals(2, expansion.size());
    assertEquals("jazz piano", expansion.get("data").get("original").asText());
    // jazz and piano repeat the query and are not shown.
    assertEquals(
        List.of("swing", "big band"), strings(expansion.get("data").get("expanded_terms")));

    JsonNode search = client.next();
    assertEquals("keyword_search", search.get("stage").asText());
    assertTrue(search.get("data").get("time_ms").isIntegralNumber());
    JsonNode results = search.get("data").get("results");
    // The expansion brings in BIGBAND, below the document that matches the user's words.
    assertEquals(List.of("KINDOFBLUE", "BIGBAND"), ids(results));
    for (JsonNode result : results) {
      assertEquals(3, result.size());
      assertTrue(result.get("doc_id").isTextual());
      assertTrue(result.get("score").isNumber());
      assertTrue(result.get("snippet").isTextual());
    }

    assertEquals(JSON.readTree("{\"stage\": \"done\"}"), client.next());
  }

  @Test
  void unavailableOllamaGivesEmptyExpansionAndPlainKeywordResults() throws Exception {
    client.close();
    app.stop();
    app = FakeOllama.offlineServer(new IndexService(dataset)).start(0);
    client = connect();
    build();

    client.send("{\"query\": \"jazz piano\"}");
    JsonNode expansion = client.next();
    assertEquals(0, expansion.get("data").get("expanded_terms").size());
    JsonNode results = client.next().get("data").get("results");
    assertEquals("done", client.next().get("stage").asText());

    // Same results, in the same format, as POST /api/search for the original query.
    JsonNode rest =
        JSON.readTree(post("/api/search", "{\"query\": \"jazz piano\", \"mode\": \"keyword\"}"))
            .get("results");
    assertEquals(rest, results);
  }

  @Test
  void secondMessageDuringARequestIsRejectedAndIgnored() throws Exception {
    build();
    ollama.answer = "swing";
    ollama.holdGenerate = true;

    client.send("{\"query\": \"jazz\"}");
    // Jetty delivers one connection's messages in order, so the first request is already
    // accepted (and stuck in the held expansion) when this one arrives.
    client.send("{\"query\": \"guitar\"}");
    assertEquals(
        JSON.readTree("{\"stage\": \"error\", \"message\": \"request already in progress\"}"),
        client.next());

    ollama.release.countDown();
    assertEquals("jazz", client.next().get("data").get("original").asText());
    assertEquals("keyword_search", client.next().get("stage").asText());
    assertEquals("done", client.next().get("stage").asText());

    // Had "guitar" been queued rather than ignored, its stages would come before these.
    ollama.answer = "";
    client.send("{\"query\": \"riffs\"}");
    assertEquals("riffs", client.next().get("data").get("original").asText());
    assertEquals(List.of("METAL"), ids(client.next().get("data").get("results")));
    assertEquals("done", client.next().get("stage").asText());
  }

  @Test
  void malformedMessagesGetAnErrorAndTheConnectionStaysUsable() throws Exception {
    build();
    for (String bad : List.of("hello", "{}", "[]", "{\"query\": 5}", "{\"query\": null}")) {
      client.send(bad);
      JsonNode error = client.next();
      assertEquals("error", error.get("stage").asText(), bad);
      assertTrue(error.get("message").isTextual());
    }
    client.send("{\"query\": \"guitar\"}");
    assertEquals("query_expansion", client.next().get("stage").asText());
    assertEquals(List.of("METAL"), ids(client.next().get("data").get("results")));
    assertEquals("done", client.next().get("stage").asText());
  }

  @Test
  void queryBeforeAnyBuildIsAnErrorWithoutCallingOllama() throws Exception {
    client.send("{\"query\": \"jazz\"}");
    assertEquals(
        JSON.readTree("{\"stage\": \"error\", \"message\": \"index has not been built yet\"}"),
        client.next());
    assertEquals(0, ollama.generateCalls.get());

    build();
    client.send("{\"query\": \"jazz\"}");
    assertEquals("query_expansion", client.next().get("stage").asText());
  }

  @Test
  void connectionsAreIndependent() throws Exception {
    build();
    ollama.holdGenerate = true;
    client.send("{\"query\": \"jazz\"}");

    // A request held on one connection does not make another connection busy.
    try (Client other = connect()) {
      other.send("not json");
      assertEquals("message must be {\"query\": string}", other.next().get("message").asText());
    }
    ollama.release.countDown();
    assertEquals("query_expansion", client.next().get("stage").asText());
  }

  @Test
  void requestsBeyondWorkersPlusQueueAreRejectedAsBusy() throws Exception {
    build();
    ollama.holdGenerate = true;
    int limit = SearchSocket.WORKERS + 2 * SearchSocket.WORKERS; // 8 running + 16 queued
    JsonNode inProgress =
        JSON.readTree("{\"stage\": \"error\", \"message\": \"request already in progress\"}");

    List<Client> held = new ArrayList<>();
    try {
      for (int i = 0; i < limit; i++) {
        Client c = connect();
        held.add(c);
        c.send("{\"query\": \"held " + i + "\"}");
        // Messages of one connection are handled in order, so this reply proves the first one
        // was fully accepted (counted in flight) before the next connection starts.
        c.send("{\"query\": \"again\"}");
        assertEquals(inProgress, c.next());
      }
      // Only the eight workers reach Ollama; the other sixteen wait in the pool queue. Workers
      // pick up their tasks asynchronously, so wait (bounded) until all eight are held.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (ollama.generateCalls.get() < SearchSocket.WORKERS) {
        assertTrue(System.nanoTime() < deadline, "workers did not reach Ollama within 10 s");
        Thread.onSpinWait();
      }
      assertEquals(SearchSocket.WORKERS, ollama.generateCalls.get());

      Client extra = connect();
      held.add(extra);
      extra.send("{\"query\": \"one too many\"}");
      assertEquals(
          JSON.readTree("{\"stage\": \"error\", \"message\": \"server busy, try again later\"}"),
          extra.next());

      ollama.release.countDown();
      for (int i = 0; i < limit; i++) {
        Client c = held.get(i);
        assertEquals("held " + i, c.next().get("data").get("original").asText());
        assertEquals("keyword_search", c.next().get("stage").asText());
        assertEquals("done", c.next().get("stage").asText());
      }
      assertEquals(limit, ollama.generateCalls.get());

      // With everything finished there is room again.
      extra.send("{\"query\": \"jazz\"}");
      assertEquals("query_expansion", extra.next().get("stage").asText());
      assertEquals("keyword_search", extra.next().get("stage").asText());
      assertEquals("done", extra.next().get("stage").asText());
    } finally {
      for (Client c : held) {
        c.close();
      }
    }
  }

  private void build() throws Exception {
    assertEquals(
        "ok", JSON.readTree(post("/api/index", "{\"threads\": 2}")).get("status").asText());
  }

  private String post(String path, String body) throws Exception {
    return http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString())
        .body();
  }

  private Client connect() throws Exception {
    return new Client(http, URI.create("ws://localhost:" + app.port() + "/ws/search"));
  }

  private static List<String> ids(JsonNode results) {
    List<String> ids = new ArrayList<>();
    results.forEach(r -> ids.add(r.get("doc_id").asText()));
    return ids;
  }

  private static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.asText()));
    return out;
  }

  /** Collects whole text messages; {@link #next()} waits for one with a deadline. */
  private static final class Client implements WebSocket.Listener, AutoCloseable {
    private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    private final StringBuilder partial = new StringBuilder();
    private final WebSocket socket;

    Client(HttpClient http, URI uri) throws Exception {
      socket = http.newWebSocketBuilder().buildAsync(uri, this).get(10, TimeUnit.SECONDS);
    }

    void send(String text) throws Exception {
      socket.sendText(text, true).get(10, TimeUnit.SECONDS);
    }

    JsonNode next() throws Exception {
      String message = messages.poll(15, TimeUnit.SECONDS);
      assertNotNull(message, "no message within 15 s");
      return JSON.readTree(message);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      partial.append(data);
      if (last) {
        messages.add(partial.toString());
        partial.setLength(0);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public void close() {
      socket.abort();
    }
  }
}
