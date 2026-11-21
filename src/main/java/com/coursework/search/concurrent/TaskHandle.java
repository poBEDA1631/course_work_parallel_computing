package com.coursework.search.concurrent;

import java.util.concurrent.ExecutionException;

public interface TaskHandle<T> {
  /**
   * Blocks until the task finishes.
   *
   * @throws ExecutionException if the task threw; the original throwable is the cause
   */
  T get() throws InterruptedException, ExecutionException;

  boolean isDone();
}
