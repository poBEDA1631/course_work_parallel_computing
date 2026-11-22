package com.coursework.search.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coursework.search.query.KeywordSearch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SearchApiTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient client = HttpClient.newHttpClient();

  @TempDir Path dataset;
  private Javalin app;

  @BeforeEach
  void start() throws Exception {
    write(
        "INTERSTELLAR",
        "Interstellar\nAstronauts travel through space and time.\n\n"
            + "Space Odyssey\nThe best space movie, space everywhere.");
    write("APOLLO", "Apollo 13\nA space mission goes wrong.\n\nTense and true.");
    write("GRAVITY", "Gravity\nStranded in orbit.\n\nSpace looks beautiful here.");
    write("KINDOFBLUE", "Kind of Blue\nModal jazz record.\n\nThe best jazz album ever.");
    write("GODFATHER", "The Godfather\nCrime family saga.\n\nGreat movies like this are rare.");
    write(
        "FESTIVAL",
        "Festival Live\n"
            + "Concert footage and crowd shots. ".repeat(12)
            + "\n\nSo many parties!");
    app = ApiServer.create(new IndexService(dataset)).start(0);
  }

  @AfterEach
  void stop() {
    app.stop();
  }

  @Test
  void searchBeforeAnyBuildIsConflict() throws Exception {
    assertError(search("space", "keyword"), 409);
  }

  @Test
  void rankedResultsHaveTheContractShape() throws Exception {
    build();
    HttpResponse<String> response = search("good movies about space", "keyword");
    assertEquals(200, response.statusCode(), response.body());
    JsonNode body = JSON.readTree(response.body());
    assertEquals(2, body.size());
    assertTrue(body.get("time_ms").isIntegralNumber());

    JsonNode results = body.get("results");
    List<String> ids = ids(results);
    // "movies" stems to "movy", found only in GODFATHER: df 1 of N 6 gives idf ln(5.5/1.5 + 1)
    // ~ 1.54, while "space" (df 3) has idf ln 2 ~ 0.69, so the rare term outranks four "space"s.
    // "movie" in INTERSTELLAR stems to "movie" and does not match "movy" (known inaccuracy).
    assertEquals(List.of("GODFATHER", "INTERSTELLAR"), ids.subList(0, 2));
    assertTrue(ids.containsAll(List.of("APOLLO", "GRAVITY")), ids.toString());
    assertEquals(4, ids.size());
    assertFalse(ids.contains("KINDOFBLUE"));
    assertFalse(ids.contains("FESTIVAL"));

    double previous = Double.MAX_VALUE;
    for (JsonNode result : results) {
      assertEquals(3, result.size());
      assertTrue(result.get("doc_id").isTextual());
      assertTrue(result.get("snippet").isTextual());
      double score = result.get("score").asDouble();
      assertTrue(score <= previous && score >= 0 && score <= 1, result.toString());
      previous = score;
    }
    assertEquals(1.0, results.get(0).get("score").asDouble());
    assertEquals(0.0, results.get(results.size() - 1).get("score").asDouble());
    assertTrue(results.get(0).get("snippet").asText().contains("movies"));
  }

  @Test
  void higherTermFrequencyRanksFirstForASingleTerm() throws Exception {
    build();
    JsonNode results = JSON.readTree(search("SPACE", "keyword").body()).get("results");
    // tf 4 in INTERSTELLAR versus tf 1 in APOLLO and GRAVITY.
    assertEquals("INTERSTELLAR", results.get(0).get("doc_id").asText());
    assertEquals(3, results.size());
    assertTrue(results.get(0).get("snippet").asText().contains("space"));
  }

  @Test
  void snippetFallsBackToTheStartWhenOnlyAStemMatched() throws Exception {
    build();
    // "party" and "parties" both stem to "party", but "party" never occurs literally.
    JsonNode results = JSON.readTree(search("party", "keyword").body()).get("results");
    assertEquals(1, results.size());
    assertEquals("FESTIVAL", results.get(0).get("doc_id").asText());
    assertEquals(1.0, results.get(0).get("score").asDouble());
    String snippet = results.get(0).get("snippet").asText();
    assertTrue(snippet.startsWith("Festival Live Concert footage"), snippet);
    assertFalse(snippet.contains("parties"), snippet);
  }

  @Test
  void returnsAtMostTopK() throws Exception {
    for (int i = 0; i < 15; i++) {
      write("EXTRA" + i, "space filler " + i);
    }
    build();
    JsonNode results = JSON.readTree(search("space", "keyword").body()).get("results");
    assertEquals(KeywordSearch.K, results.size());
  }

  @Test
  void queryWithNoIndexedTermReturnsEmptyResults() throws Exception {
    build();
    for (String query : List.of("zzzqqq", "", "!!! ...")) {
      HttpResponse<String> response = search(query, "keyword");
      assertEquals(200, response.statusCode());
      assertEquals(0, JSON.readTree(response.body()).get("results").size());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "not json",
        "[]",
        "{}",
        "{\"query\": \"space\"}",
        "{\"query\": \"space\", \"mode\": \"semantic\"}",
        "{\"query\": \"space\", \"mode\": \"KEYWORD\"}",
        "{\"query\": \"space\", \"mode\": 1}",
        "{\"query\": 42, \"mode\": \"keyword\"}",
        "{\"query\": null, \"mode\": \"keyword\"}",
        "{\"mode\": \"keyword\"}"
      })
  void invalidSearchRequestIsBadRequest(String body) throws Exception {
    build();
    assertError(post("/api/search", body), 400);
  }

  private void build() throws Exception {
    assertEquals(200, post("/api/index", "{\"threads\": 2}").statusCode());
  }

  private void write(String docId, String text) throws Exception {
    Files.writeString(dataset.resolve(docId + ".txt"), text);
  }

  private HttpResponse<String> search(String query, String mode) throws Exception {
    return post(
        "/api/search",
        JSON.writeValueAsString(JSON.createObjectNode().put("query", query).put("mode", mode)));
  }

  private static List<String> ids(JsonNode results) {
    List<String> ids = new ArrayList<>();
    results.forEach(r -> ids.add(r.get("doc_id").asText()));
    return ids;
  }

  private static void assertError(HttpResponse<String> response, int status) throws Exception {
    assertEquals(status, response.statusCode(), response.body());
    JsonNode body = JSON.readTree(response.body());
    assertEquals("error", body.get("status").asText());
    assertTrue(body.get("message").isTextual());
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
