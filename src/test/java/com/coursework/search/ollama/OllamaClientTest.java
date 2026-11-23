package com.coursework.search.ollama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class OllamaClientTest {
  private static final String MODEL = "llama3.2:3b";

  private StubOllama stub;
  private OllamaClient client;

  @BeforeEach
  void start() throws IOException {
    stub = new StubOllama();
    client = new OllamaClient(stub.url(), MODEL, Duration.ofMillis(300), Duration.ofMillis(300));
  }

  @AfterEach
  void stop() {
    stub.close();
  }

  @Test
  void generateReturnsResponseTextAndSendsDeterministicNonStreamingRequest() throws Exception {
    stub.respondWithText(" horror, thriller");
    assertEquals(" horror, thriller", client.generate("the prompt"));

    JsonNode sent = new ObjectMapper().readTree(stub.lastGenerateBody.get());
    assertEquals(MODEL, sent.get("model").asText());
    assertEquals("the prompt", sent.get("prompt").asText());
    assertFalse(sent.get("stream").asBoolean());
    assertEquals(0, sent.get("options").get("temperature").asInt());
  }

  @Test
  void generateSendsExactlyTheExpansionBody() throws Exception {
    stub.respondWithText("x");
    client.generate("the prompt");
    assertEquals(
        "{\"model\":\"llama3.2:3b\",\"prompt\":\"the prompt\",\"stream\":false,"
            + "\"options\":{\"temperature\":0}}",
        stub.lastGenerateBody.get());
  }

  @Test
  void generateWithMaxTokensAddsNumPredict() throws Exception {
    stub.respondWithText("x");
    assertEquals("x", client.generate("the prompt", 1));
    assertEquals(
        "{\"model\":\"llama3.2:3b\",\"prompt\":\"the prompt\",\"stream\":false,"
            + "\"options\":{\"temperature\":0,\"num_predict\":1}}",
        stub.lastGenerateBody.get());
    assertThrows(IllegalArgumentException.class, () -> client.generate("p", 0));
  }

  @Test
  void generateFailsOnErrorStatus() {
    stub.generateStatus = 500;
    stub.generateBody = "{\"error\": \"model not found\"}";
    IOException e = assertThrows(IOException.class, () -> client.generate("p"));
    assertTrue(e.getMessage().contains("500"), e.getMessage());
  }

  @Test
  void generateFailsOnBodyWithoutText() {
    stub.generateBody = "{\"done\": true}";
    assertThrows(IOException.class, () -> client.generate("p"));
    stub.generateBody = "not json";
    assertThrows(IOException.class, () -> client.generate("p"));
  }

  @Test
  void generateTimesOutWhenTheServerDoesNotAnswer() {
    stub.holdGenerate = true;
    assertThrows(HttpTimeoutException.class, () -> client.generate("p"));
  }

  @Test
  void generateFailsWhenTheServerIsUnreachable() throws Exception {
    OllamaClient offline = new OllamaClient(closedPortUrl(), MODEL);
    assertThrows(IOException.class, () -> offline.generate("p"));
  }

  @Test
  void availableOnlyWhenTheModelIsListed() throws Exception {
    stub.tagsBody = "{\"models\": [{\"name\": \"other:1b\"}, {\"name\": \"llama3.2:3b\"}]}";
    assertTrue(client.isAvailable());
    stub.tagsBody = "{\"models\": [{\"name\": \"other:1b\"}]}";
    assertFalse(client.isAvailable());
    stub.tagsBody = "{\"models\": []}";
    assertFalse(client.isAvailable());
  }

  @Test
  void unavailableOnErrorSlownessOrNoServer() throws Exception {
    stub.tagsStatus = 503;
    assertFalse(client.isAvailable());
    stub.tagsStatus = 200;
    stub.tagsBody = "garbage";
    assertFalse(client.isAvailable());
    stub.holdTags = true;
    assertFalse(client.isAvailable());
    assertFalse(new OllamaClient(closedPortUrl(), MODEL).isAvailable());
  }

  /** A port that was just free; nothing listens on it, so connections are refused. */
  private static String closedPortUrl() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return "http://127.0.0.1:" + socket.getLocalPort();
    }
  }
}
