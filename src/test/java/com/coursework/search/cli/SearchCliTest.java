package com.coursework.search.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coursework.search.api.ApiServer;
import com.coursework.search.api.IndexService;
import com.coursework.search.ollama.OllamaClient;
import com.coursework.search.ollama.QueryExpansionCache;
import com.coursework.search.ollama.QueryExpansionService;
import io.javalin.Javalin;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SearchCliTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();
  private final AtomicInteger connections = new AtomicInteger();
  private final AtomicReference<String> received = new AtomicReference<>();
  private Javalin server;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop();
    }
  }

  @Test
  void printsEachStageAsItArrives() {
    int port =
        fakeServer(
            List.of(
                "{\"stage\":\"query_expansion\",\"data\":{\"original\":\"jazz piano\","
                    + "\"expanded_terms\":[\"swing\",\"big band\"]}}",
                "{\"stage\":\"keyword_search\",\"data\":{\"results\":["
                    + "{\"doc_id\":\"B000005GZH\",\"score\":1.0,\"snippet\":\"Wonderful piano\"},"
                    + "{\"doc_id\":\"B0013RDZXI\",\"score\":0.4567,\"snippet\":\"Jazz Hat\"}"
                    + "],\"time_ms\":44}}",
                "{\"stage\":\"done\"}"));

    assertEquals(0, run("jazz", "piano", "--port", String.valueOf(port)));
    assertEquals("{\"query\":\"jazz piano\"}", received.get());
    assertEquals(
        String.join(
            "\n",
            "Expanding query...",
            "Expanded with: swing, big band",
            "",
            "Results (2 found, 44 ms):",
            "1. [1.00] B000005GZH",
            "   Wonderful piano",
            "2. [0.46] B0013RDZXI",
            "   Jazz Hat",
            ""),
        stdout());
    assertEquals("", stderr());
  }

  @Test
  void emptyExpansionAndEmptyResults() {
    int port =
        fakeServer(
            List.of(
                "{\"stage\":\"query_expansion\",\"data\":{\"original\":\"q\","
                    + "\"expanded_terms\":[]}}",
                "{\"stage\":\"keyword_search\",\"data\":{\"results\":[],\"time_ms\":3}}",
                "{\"stage\":\"done\"}"));

    assertEquals(0, run("qqqzzz", "--port", String.valueOf(port)));
    assertEquals(
        String.join(
            "\n",
            "Expanding query...",
            "No expansion.",
            "",
            "Results (0 found, 3 ms):",
            "No results.",
            ""),
        stdout());
  }

  @Test
  void emptySnippetPrintsNoSnippetLine() {
    int port =
        fakeServer(
            List.of(
                "{\"stage\":\"query_expansion\",\"data\":{\"original\":\"q\","
                    + "\"expanded_terms\":[]}}",
                "{\"stage\":\"keyword_search\",\"data\":{\"results\":["
                    + "{\"doc_id\":\"A\",\"score\":1,\"snippet\":\"\"}],\"time_ms\":1}}",
                "{\"stage\":\"done\"}"));
    assertEquals(0, run("q", "--port", String.valueOf(port)));
    assertTrue(stdout().endsWith("Results (1 found, 1 ms):\n1. [1.00] A\n"), stdout());
  }

  @Test
  void serverErrorGoesToStderrWithExitCodeOne() {
    int port =
        fakeServer(List.of("{\"stage\":\"error\",\"message\":\"index has not been built yet\"}"));
    assertEquals(1, run("jazz", "--port", String.valueOf(port)));
    assertEquals("Expanding query...\n", stdout());
    assertEquals("Error: index has not been built yet\n", stderr());
  }

  @Test
  void errorAfterExpansionStillExitsOne() {
    int port =
        fakeServer(
            List.of(
                "{\"stage\":\"query_expansion\",\"data\":{\"original\":\"q\","
                    + "\"expanded_terms\":[]}}",
                "{\"stage\":\"error\",\"message\":\"keyword search failed\"}"));
    assertEquals(1, run("q", "--port", String.valueOf(port)));
    assertEquals("Expanding query...\nNo expansion.\n", stdout());
    assertEquals("Error: keyword search failed\n", stderr());
  }

  @Test
  void silentServerTimesOut() {
    int port = fakeServer(List.of());
    assertEquals(1, cli(Duration.ofMillis(300), "q", "--port", String.valueOf(port)));
    assertEquals("Error: no response from server within 300 ms\n", stderr());
  }

  @Test
  void connectionClosedBeforeDoneIsAnError() {
    int port =
        fakeServer(
            List.of(
                "{\"stage\":\"query_expansion\",\"data\":{\"original\":\"q\","
                    + "\"expanded_terms\":[]}}",
                "CLOSE"));
    assertEquals(1, run("q", "--port", String.valueOf(port)));
    assertEquals("Error: connection closed by server\n", stderr());
  }

  @Test
  void unreachableServerIsReportedWithTheUrl() throws Exception {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    assertEquals(1, run("q", "--host", "127.0.0.1", "--port", String.valueOf(port)));
    assertEquals(
        "Could not connect to ws://127.0.0.1:"
            + port
            + "/ws/search: connection refused (is the server running?)\n",
        stderr());
    assertEquals("", stdout());
  }

  @Test
  void unknownHostIsReported() {
    assertEquals(1, run("q", "--host", "no-such-host.invalid"));
    assertEquals(
        "Could not connect to ws://no-such-host.invalid:8080/ws/search: unknown host\n", stderr());
  }

  @Test
  void serverWithoutTheEndpointIsReported() {
    server = Javalin.create().start(0);
    assertEquals(1, run("q", "--host", "127.0.0.1", "--port", String.valueOf(server.port())));
    assertEquals(
        "Could not connect to ws://127.0.0.1:"
            + server.port()
            + "/ws/search: no WebSocket endpoint here (HTTP 404)\n",
        stderr());
  }

  @Test
  void missingQueryPrintsUsageAndNeverConnects() {
    int port = fakeServer(List.of());
    assertEquals(1, run("--port", String.valueOf(port)));
    assertTrue(stderr().startsWith("Error: no query given\nUsage: search "), stderr());
    assertEquals("", stdout());
    assertEquals(0, connections.get());
  }

  @Test
  void endToEndAgainstTheRealServer(@TempDir Path dataset) throws Exception {
    Files.writeString(dataset.resolve("KINDOFBLUE.txt"), "Kind of Blue\nJazz piano trio record");
    Files.writeString(dataset.resolve("METAL.txt"), "Master of Puppets\nHeavy guitar riffs");
    IndexService index = new IndexService(dataset);
    index.rebuild(2);
    OllamaClient offline;
    try (ServerSocket socket = new ServerSocket(0)) {
      offline = new OllamaClient("http://127.0.0.1:" + socket.getLocalPort(), "llama3.2:3b");
    }
    server =
        ApiServer.create(
                index, offline, new QueryExpansionService(offline, new QueryExpansionCache()))
            .start(0);

    assertEquals(0, run("jazz", "piano", "--port", String.valueOf(server.port())));
    String[] lines = stdout().split("\n");
    assertEquals("Expanding query...", lines[0]);
    assertEquals("No expansion.", lines[1]);
    assertEquals("", lines[2]);
    assertTrue(lines[3].matches("Results \\(1 found, \\d+ ms\\):"), lines[3]);
    assertEquals("1. [1.00] KINDOFBLUE", lines[4]);
    assertEquals("   Kind of Blue Jazz piano trio record", lines[5]);
    assertEquals(6, lines.length);
  }

  /** Answers every query with {@code replies} in order; "CLOSE" closes the connection. */
  private int fakeServer(List<String> replies) {
    server =
        Javalin.create()
            .ws(
                "/ws/search",
                ws -> {
                  ws.onConnect(ctx -> connections.incrementAndGet());
                  ws.onMessage(
                      ctx -> {
                        received.set(ctx.message());
                        for (String reply : replies) {
                          if (reply.equals("CLOSE")) {
                            ctx.closeSession();
                            return;
                          }
                          ctx.send(reply);
                        }
                      });
                })
            .start(0);
    return server.port();
  }

  private int run(String... args) {
    return cli(TIMEOUT, args);
  }

  private int cli(Duration timeout, String... args) {
    return SearchCli.run(
        args,
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8),
        timeout);
  }

  private String stdout() {
    return out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
  }

  private String stderr() {
    return err.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
  }
}
