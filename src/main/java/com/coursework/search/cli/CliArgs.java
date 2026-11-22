package com.coursework.search.cli;

/** Parsed command line: {@code [--host H] [--port P] word...}. */
record CliArgs(String host, int port, String query) {
  static final String DEFAULT_HOST = "localhost";
  static final int DEFAULT_PORT = 8080;

  static final String USAGE =
      """
      Usage: search [--host HOST] [--port PORT] QUERY...
        Sends QUERY to a running server over ws://HOST:PORT/ws/search.
        --host  server host (default localhost)
        --port  server port (default 8080)""";

  /** Thrown for any command line that must not reach the server. */
  static final class UsageException extends Exception {
    UsageException(String message) {
      super(message);
    }
  }

  /** Positional words, in order and joined by single spaces, form the query. */
  static CliArgs parse(String[] args) throws UsageException {
    String host = DEFAULT_HOST;
    int port = DEFAULT_PORT;
    StringBuilder query = new StringBuilder();
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (arg.equals("--host")) {
        host = valueOf(args, ++i, "--host");
      } else if (arg.equals("--port")) {
        port = parsePort(valueOf(args, ++i, "--port"));
      } else if (arg.startsWith("--")) {
        throw new UsageException("unknown option " + arg);
      } else if (!arg.isBlank()) {
        if (!query.isEmpty()) {
          query.append(' ');
        }
        query.append(arg.strip());
      }
    }
    if (query.isEmpty()) {
      throw new UsageException("no query given");
    }
    return new CliArgs(host, port, query.toString());
  }

  String url() {
    return "ws://" + host + ":" + port + "/ws/search";
  }

  private static String valueOf(String[] args, int i, String flag) throws UsageException {
    if (i >= args.length || args[i].startsWith("--") || args[i].isBlank()) {
      throw new UsageException(flag + " needs a value");
    }
    return args[i];
  }

  private static int parsePort(String value) throws UsageException {
    try {
      int port = Integer.parseInt(value);
      if (port >= 1 && port <= 65535) {
        return port;
      }
    } catch (NumberFormatException e) {
      // Reported below together with out-of-range values.
    }
    throw new UsageException("--port must be a number from 1 to 65535: " + value);
  }
}
