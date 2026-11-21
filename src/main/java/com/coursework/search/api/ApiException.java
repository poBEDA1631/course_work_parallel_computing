package com.coursework.search.api;

/** Rendered as {@code {"status": "error", "message": ...}} with the given HTTP status. */
public final class ApiException extends RuntimeException {
  private final int status;

  public ApiException(int status, String message) {
    super(message);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
