package com.coursework.search.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal client for an Ollama server. Failures are reported as exceptions so that callers decide
 * how to degrade.
 */
public final class OllamaClient {
  public static final Duration GENERATE_TIMEOUT = Duration.ofSeconds(5);
  public static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(1);

  private static final ObjectMapper JSON = new ObjectMapper();

  private final URI baseUrl;
  private final String model;
  private final Duration generateTimeout;
  private final Duration healthTimeout;
  private final HttpClient http;

  public OllamaClient(String baseUrl, String model) {
    this(baseUrl, model, GENERATE_TIMEOUT, HEALTH_TIMEOUT);
  }

  OllamaClient(String baseUrl, String model, Duration generateTimeout, Duration healthTimeout) {
    this.baseUrl = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
    this.model = model;
    this.generateTimeout = generateTimeout;
    this.healthTimeout = healthTimeout;
    this.http = HttpClient.newBuilder().connectTimeout(healthTimeout).build();
  }

  public String model() {
    return model;
  }

  /**
   * Runs a non-streaming completion with temperature 0 and returns the model's text.
   *
   * @throws IOException on connection failure, timeout, a non-200 status or a body without a
   *     textual {@code response} field
   */
  public String generate(String prompt) throws IOException, InterruptedException {
    ObjectNode body = JSON.createObjectNode().put("model", model).put("prompt", prompt);
    body.put("stream", false);
    body.putObject("options").put("temperature", 0);
    HttpRequest request =
        HttpRequest.newBuilder(baseUrl.resolve("api/generate"))
            .timeout(generateTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("ollama generate returned HTTP " + response.statusCode());
    }
    JsonNode text = JSON.readTree(response.body()).get("response");
    if (text == null || !text.isTextual()) {
      throw new IOException("ollama generate response has no text");
    }
    return text.asText();
  }

  /**
   * Whether the server answers {@code GET /api/tags} within the health timeout and lists the
   * configured model; a reachable server that has not pulled the model yet is not available.
   */
  public boolean isAvailable() throws InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(baseUrl.resolve("api/tags")).timeout(healthTimeout).GET().build();
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return false;
      }
      JsonNode models = JSON.readTree(response.body()).get("models");
      if (models == null || !models.isArray()) {
        return false;
      }
      for (JsonNode entry : models) {
        if (model.equals(entry.path("name").asText())
            || model.equals(entry.path("model").asText())) {
          return true;
        }
      }
      return false;
    } catch (IOException e) {
      return false;
    }
  }
}
