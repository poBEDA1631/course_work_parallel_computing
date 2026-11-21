package com.coursework.search.concurrent;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

public final class ThreadPool {
  private final BoundedTaskQueue queue;
  private final Thread[] workers;

  public ThreadPool(int workerCount) {
    if (workerCount < 1) {
      throw new IllegalArgumentException("workerCount must be >= 1: " + workerCount);
    }
    queue = new BoundedTaskQueue(2 * workerCount);
    workers = new Thread[workerCount];
    for (int i = 0; i < workerCount; i++) {
      workers[i] = new Thread(this::workLoop, "pool-worker-" + i);
      workers[i].start();
    }
  }

  /**
   * Blocks while the queue is full.
   *
   * @throws IllegalStateException if the pool is shut down
   */
  public <T> TaskHandle<T> submit(Callable<T> callable) throws InterruptedException {
    Task<T> task = new Task<>(callable);
    queue.put(task);
    return task;
  }

  /**
   * Blocks while the queue is full.
   *
   * @throws IllegalStateException if the pool is shut down
   */
  public TaskHandle<Void> submit(Runnable runnable) throws InterruptedException {
    if (runnable == null) {
      throw new NullPointerException("runnable");
    }
    return submit(
        () -> {
          runnable.run();
          return null;
        });
  }

  /** Rejects new tasks; tasks already queued still run. */
  public void shutdown() {
    queue.close();
  }

  /** Returns {@code true} if all workers exited before the timeout elapsed. */
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    for (Thread worker : workers) {
      long remaining = Math.max(0, deadline - System.nanoTime());
      if (!worker.join(Duration.ofNanos(remaining))) {
        return false;
      }
    }
    return true;
  }

  public int workerCount() {
    return workers.length;
  }

  public int queueCapacity() {
    return queue.capacity();
  }

  private void workLoop() {
    Task<?> task;
    while ((task = queue.take()) != null) {
      task.run();
      // A task may leave the interrupt flag set; it must not leak into the next task.
      Thread.interrupted();
    }
  }
}
