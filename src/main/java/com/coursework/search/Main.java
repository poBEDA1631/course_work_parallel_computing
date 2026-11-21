package com.coursework.search;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import java.util.Map;

public final class Main {
  private static final int DEFAULT_PORT = 8080;

  private Main() {}

  public static void main(String[] args) {
    createApp().start(port());
  }

  static Javalin createApp() {
    Javalin app = Javalin.create(config -> config.staticFiles.add("/static", Location.CLASSPATH));
    app.get("/api/health", ctx -> ctx.json(Map.of("status", "ok", "ollama", false)));
    return app;
  }

  private static int port() {
    String value = System.getenv("PORT");
    if (value == null || value.isBlank()) {
      return DEFAULT_PORT;
    }
    return Integer.parseInt(value.trim());
  }
}
