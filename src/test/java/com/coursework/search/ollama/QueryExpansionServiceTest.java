package com.coursework.search.ollama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class QueryExpansionServiceTest {
  private StubOllama stub;
  private QueryExpansionService service;

  @BeforeEach
  void start() throws IOException {
    stub = new StubOllama();
    service = serviceFor(stub.url());
  }

  @AfterEach
  void stop() {
    stub.close();
  }

  @Test
  void parsesCommaSeparatedAnswer() {
    assertEquals(
        List.of("horror", "thriller", "suspense"),
        QueryExpansionService.parse("horror, thriller, suspense"));
  }

  @Test
  void trimsAndLowerCases() {
    assertEquals(
        List.of("science fiction", "galaxy", "outer space"),
        QueryExpansionService.parse("  Science Fiction ,GALAXY,\n Outer Space  "));
  }

  @Test
  void dropsEmptyEntriesAndDuplicatesKeepingFirstOrder() {
    assertEquals(
        List.of("jazz", "piano", "blues"),
        QueryExpansionService.parse("jazz, , Piano,,jazz, blues, PIANO ,"));
  }

  @Test
  void keepsAtMostEightTerms() {
    assertEquals(
        List.of("a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8"),
        QueryExpansionService.parse("a1, a2, a3, a4, a5, a6, a7, a8, a9, a10"));
    // Duplicates do not use up the limit.
    assertEquals(8, QueryExpansionService.parse("x, x, x, b, c, d, e, f, g, h, i").size());
  }

  @Test
  void fewerTermsAreKeptAsIs() {
    assertEquals(List.of("ballad"), QueryExpansionService.parse("ballad"));
  }

  @Test
  void emptyOrGarbageAnswerGivesNoTerms() {
    assertEquals(List.of(), QueryExpansionService.parse(""));
    assertEquals(List.of(), QueryExpansionService.parse("   ,  , "));
    assertEquals(List.of(), QueryExpansionService.parse("I cannot help with that request"));
    assertEquals(
        List.of(), QueryExpansionService.parse("Here are some related keywords for your query"));
    assertEquals(List.of(), QueryExpansionService.parse("x".repeat(41)));
  }

  @Test
  void shortFragmentOfACommaSeparatedSentenceSurvives() {
    // Known limitation of the length rule: only the long part of a refusal is dropped.
    assertEquals(
        List.of("i'm sorry"),
        QueryExpansionService.parse("I'm sorry, I cannot help with that request"));
  }

  @Test
  void sentenceLikeEntriesAreDroppedButKeywordsKept() {
    assertEquals(
        List.of("horror", "slasher"),
        QueryExpansionService.parse("horror, here is a list of words, slasher"));
  }

  @Test
  void promptIsTheFixedFewShotTemplateWithTheQuery() {
    String expected =
        "You expand short search queries into related keywords for a movie and music review"
            + " search engine. Respond with ONLY a comma-separated list of 3 to 8 related keywords,"
            + " no explanation.\n\n"
            + "Query: \"scary movies\"\n"
            + "Keywords: horror, thriller, suspense, halloween, frightening, slasher\n\n"
            + "Query: \"guitar music\"\n"
            + "Keywords: rock, acoustic, strings, instrumental, band, blues\n\n"
            + "Query: \"Space Movies\"\n"
            + "Keywords:";
    assertEquals(expected, QueryExpansionService.prompt("Space Movies"));
  }

  @Test
  void successfulAnswerIsParsedAndSentWithTheQuery() throws Exception {
    stub.respondWithText(" Science Fiction, galaxy, galaxy, ALIENS");
    assertEquals(List.of("science fiction", "galaxy", "aliens"), service.expand("space movies"));
    String prompt = new ObjectMapper().readTree(stub.lastGenerateBody.get()).get("prompt").asText();
    assertTrue(prompt.endsWith("Query: \"space movies\"\nKeywords:"), prompt);
  }

  @Test
  void repeatedQueryIsServedFromCacheUsingTheNormalizedKey() {
    stub.respondWithText("horror, thriller");
    assertEquals(List.of("horror", "thriller"), service.expand("Scary Movies"));
    assertEquals(List.of("horror", "thriller"), service.expand("  scary movies "));
    assertEquals(List.of("horror", "thriller"), service.expand("SCARY MOVIES"));
    assertEquals(1, stub.generateCalls.get());
  }

  @Test
  void parsedButEmptyAnswerIsCachedToo() {
    stub.respondWithText("I cannot help with that");
    assertEquals(List.of(), service.expand("q"));
    assertEquals(List.of(), service.expand("q"));
    assertEquals(1, stub.generateCalls.get());
  }

  @Test
  void timeoutGivesNoTermsAndIsNotCached() {
    stub.holdGenerate = true;
    assertEquals(List.of(), service.expand("slow query"));
    assertEquals(1, stub.generateCalls.get());
    stub.holdGenerate = false;
    stub.hold.countDown();
    stub.respondWithText("fast, answer");
    assertEquals(List.of("fast", "answer"), service.expand("slow query"));
    assertEquals(2, stub.generateCalls.get());
  }

  @Test
  void serverErrorGivesNoTermsAndIsNotCached() {
    stub.generateStatus = 500;
    assertEquals(List.of(), service.expand("q"));
    stub.generateStatus = 200;
    stub.respondWithText("ok");
    assertEquals(List.of("ok"), service.expand("q"));
  }

  @Test
  void malformedBodyGivesNoTerms() {
    stub.generateBody = "{\"unexpected\": 1}";
    assertEquals(List.of(), service.expand("q"));
  }

  @Test
  void unreachableServerGivesNoTerms() throws IOException {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    assertEquals(List.of(), serviceFor("http://127.0.0.1:" + port).expand("q"));
  }

  @Test
  void warmUpSendsTheExpansionPromptForOneTokenAndCachesNothing() throws Exception {
    QueryExpansionCache cache = new QueryExpansionCache();
    OllamaClient client =
        new OllamaClient(stub.url(), "llama3.2:3b", Duration.ofMillis(300), Duration.ofMillis(300));
    QueryExpansionService warmed = new QueryExpansionService(client, cache);
    stub.respondWithText("horror, thriller");

    warmed.warmUp();
    assertEquals(0, cache.size());
    assertEquals(1, stub.generateCalls.get());
    JsonNode sent = new ObjectMapper().readTree(stub.lastGenerateBody.get());
    assertEquals(
        QueryExpansionService.prompt(QueryExpansionService.WARM_UP_QUERY),
        sent.get("prompt").asText());
    assertEquals(1, sent.get("options").get("num_predict").asInt());
    assertEquals(0, sent.get("options").get("temperature").asInt());

    // The real query is still asked, not answered from anything the warm-up left behind.
    assertEquals(List.of("horror", "thriller"), warmed.expand("warm up"));
    assertEquals(2, stub.generateCalls.get());
  }

  @Test
  void warmUpNeverThrows() throws IOException {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    serviceFor("http://127.0.0.1:" + port).warmUp();
    stub.generateStatus = 500;
    service.warmUp();
    stub.generateStatus = 200;
    stub.holdGenerate = true;
    service.warmUp();
  }

  @Test
  void blankQueryDoesNotCallTheServer() {
    assertEquals(List.of(), service.expand("   "));
    assertEquals(0, stub.generateCalls.get());
  }

  private static QueryExpansionService serviceFor(String url) {
    OllamaClient client =
        new OllamaClient(url, "llama3.2:3b", Duration.ofMillis(300), Duration.ofMillis(300));
    return new QueryExpansionService(client, new QueryExpansionCache());
  }
}
