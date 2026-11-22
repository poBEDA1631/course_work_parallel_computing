package com.coursework.search.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Command-line client for {@code /ws/search}: sends one query to a running server and prints each
 * stage as it arrives. All searching happens on the server.
 */
public final class SearchCli {
  static final Duration MESSAGE_TIMEOUT = Duration.ofSeconds(30);

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SNIPPET_INDENT = "   ";

  private SearchCli() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err, MESSAGE_TIMEOUT));
  }

  /** Returns the process exit code: 0 after {@code done}, 1 for any error. */
  static int run(String[] args, PrintStream out, PrintStream err, Duration messageTimeout) {
    CliArgs cli;
    try {
      cli = CliArgs.parse(args);
    } catch (CliArgs.UsageException e) {
      err.println("Error: " + e.getMessage());
      err.println(CliArgs.USAGE);
      return 1;
    }

    Connection connection;
    try {
      connection = Connection.open(cli.url(), messageTimeout);
    } catch (IOException e) {
      err.println("Could not connect to " + cli.url() + ": " + e.getMessage());
      return 1;
    }
    try (connection) {
      connection.send(JSON.createObjectNode().put("query", cli.query()).toString());
      out.println("Expanding query...");
      while (true) {
        JsonNode message = connection.next();
        String stage = message.path("stage").asText();
        switch (stage) {
          case "query_expansion" -> printExpansion(out, message.path("data"));
          case "keyword_search" -> printResults(out, message.path("data"));
          case "done" -> {
            return 0;
          }
          case "error" -> {
            err.println("Error: " + message.path("message").asText());
            return 1;
          }
          default -> {
            err.println("Error: unexpected message from server: " + message);
            return 1;
          }
        }
      }
    } catch (IOException e) {
      err.println("Error: " + e.getMessage());
      return 1;
    }
  }

  private static void printExpansion(PrintStream out, JsonNode data) {
    List<String> terms = new ArrayList<>();
    data.path("expanded_terms").forEach(term -> terms.add(term.asText()));
    out.println(terms.isEmpty() ? "No expansion." : "Expanded with: " + String.join(", ", terms));
  }

  private static void printResults(PrintStream out, JsonNode data) {
    JsonNode results = data.path("results");
    out.println();
    out.println(
        "Results (" + results.size() + " found, " + data.path("time_ms").asLong() + " ms):");
    if (results.isEmpty()) {
      out.println("No results.");
      return;
    }
    int rank = 1;
    for (JsonNode result : results) {
      out.println(
          String.format(
              Locale.ROOT,
              "%d. [%.2f] %s",
              rank++,
              result.path("score").asDouble(),
              result.path("doc_id").asText()));
      String snippet = result.path("snippet").asText();
      if (!snippet.isEmpty()) {
        out.println(SNIPPET_INDENT + snippet);
      }
    }
  }

  /** A WebSocket whose text messages are queued whole; a close or failure ends the queue. */
  private static final class Connection implements WebSocket.Listener, AutoCloseable {
    private static final String CLOSED = "\u0000closed";

    private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    private final StringBuilder partial = new StringBuilder();
    private final Duration timeout;
    private WebSocket socket;

    private Connection(Duration timeout) {
      this.timeout = timeout;
    }

    static Connection open(String url, Duration timeout) throws IOException {
      Connection connection = new Connection(timeout);
      try {
        connection.socket =
            HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(timeout)
                .buildAsync(URI.create(url), connection)
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (ExecutionException e) {
        throw new IOException(reason(e.getCause()), e.getCause());
      } catch (TimeoutException e) {
        throw new IOException("no answer within " + describe(timeout), e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted", e);
      } catch (IllegalArgumentException e) {
        throw new IOException(e.getMessage(), e);
      }
      return connection;
    }

    void send(String text) throws IOException {
      try {
        socket.sendText(text, true).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (ExecutionException | TimeoutException e) {
        throw new IOException("could not send the query", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted", e);
      }
    }

    JsonNode next() throws IOException {
      String message;
      try {
        message = messages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted", e);
      }
      if (message == null) {
        throw new IOException("no response from server within " + describe(timeout));
      }
      if (message.equals(CLOSED)) {
        throw new IOException("connection closed by server");
      }
      try {
        return JSON.readTree(message);
      } catch (IOException e) {
        throw new IOException("malformed message from server", e);
      }
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
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      messages.add(CLOSED);
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      messages.add(CLOSED);
    }

    @Override
    public void close() {
      if (socket != null) {
        socket.abort();
      }
    }

    /**
     * The JDK client reports connect failures as message-less exceptions, so the reason is read
     * from their types.
     */
    private static String reason(Throwable cause) {
      for (Throwable t = cause; t != null; t = t.getCause()) {
        if (t instanceof UnresolvedAddressException || t instanceof UnknownHostException) {
          return "unknown host";
        }
        if (t instanceof WebSocketHandshakeException handshake) {
          return "no WebSocket endpoint here (HTTP " + handshake.getResponse().statusCode() + ")";
        }
      }
      for (Throwable t = cause; t != null; t = t.getCause()) {
        if (t instanceof ConnectException) {
          return "connection refused (is the server running?)";
        }
        if (t.getMessage() != null && !t.getMessage().isBlank()) {
          return t.getMessage();
        }
      }
      return cause.getClass().getSimpleName();
    }

    private static String describe(Duration d) {
      return d.toMillis() % 1000 == 0 ? d.toSeconds() + " s" : d.toMillis() + " ms";
    }
  }
}
