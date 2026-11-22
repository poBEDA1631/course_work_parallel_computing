package com.coursework.search.ollama;

import java.time.Duration;

/** Lets tests in other packages build a client with a custom generate timeout. */
public final class OllamaClients {
  private OllamaClients() {}

  public static OllamaClient withGenerateTimeout(String url, String model, Duration timeout) {
    return new OllamaClient(url, model, timeout, OllamaClient.HEALTH_TIMEOUT);
  }
}
