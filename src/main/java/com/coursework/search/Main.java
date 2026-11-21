package com.coursework.search;

import com.coursework.search.api.ApiServer;
import com.coursework.search.api.IndexService;
import java.nio.file.Path;

public final class Main {
  private static final int DEFAULT_PORT = 8080;
  private static final String DEFAULT_DATASET_DIR = "/data";

  private Main() {}

  public static void main(String[] args) {
    Path datasetDir = Path.of(env("DATASET_DIR", DEFAULT_DATASET_DIR));
    ApiServer.create(new IndexService(datasetDir)).start(port());
  }

  private static int port() {
    String value = env("PORT", null);
    return value == null ? DEFAULT_PORT : Integer.parseInt(value.trim());
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
