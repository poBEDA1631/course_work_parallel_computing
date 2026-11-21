package com.coursework.search.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ThreadPoolTest {
  private static final long PARK_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(10);

  private ThreadPool pool;

  /** Tasks that must not finish yet wait on this; released first in teardown even on failure. */
  private final CountDownLatch release = new CountDownLatch(1);

  @AfterEach
  void tearDown() throws InterruptedException {
    // A failed assertion would otherwise leave workers parked forever and keep the JVM alive.
    release.countDown();
    if (pool != null) {
      pool.shutdown();
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void queueCapacityIsTwiceWorkerCount() {
    pool = new ThreadPool(3);
    assertEquals(3, pool.workerCount());
    assertEquals(6, pool.queueCapacity());
  }

  @Test
  void rejectsNonPositiveWorkerCount() {
    assertThrows(IllegalArgumentException.class, () -> new ThreadPool(0));
  }

  @Test
  void concurrentProducersRunEveryTaskExactlyOnce() throws Exception {
    int producers = 8;
    int tasksPerProducer = 20_000;
    int total = producers * tasksPerProducer;
    pool = new ThreadPool(4);

    AtomicIntegerArray runs = new AtomicIntegerArray(total);
    AtomicLong sideEffectSum = new AtomicLong();
    long[] returnedSums = new long[producers];
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch start = new CountDownLatch(1);
    Thread[] threads = new Thread[producers];

    for (int p = 0; p < producers; p++) {
      int producer = p;
      threads[p] =
          new Thread(
              () -> {
                try {
                  start.await();
                  @SuppressWarnings("unchecked")
                  TaskHandle<Integer>[] handles = new TaskHandle[tasksPerProducer];
                  for (int i = 0; i < tasksPerProducer; i++) {
                    int id = producer * tasksPerProducer + i;
                    handles[i] =
                        pool.submit(
                            () -> {
                              runs.incrementAndGet(id);
                              sideEffectSum.addAndGet(id);
                              return id;
                            });
                  }
                  long sum = 0;
                  for (TaskHandle<Integer> handle : handles) {
                    sum += handle.get();
                  }
                  returnedSums[producer] = sum;
                } catch (Throwable t) {
                  failure.compareAndSet(null, t);
                }
              });
      threads[p].start();
    }
    start.countDown();
    for (Thread thread : threads) {
      thread.join();
    }

    assertNull(failure.get());
    long expected = 0;
    for (int id = 0; id < total; id++) {
      expected += id;
      assertEquals(1, runs.get(id), "task " + id);
    }
    assertEquals(expected, sideEffectSum.get());
    long returned = 0;
    for (long sum : returnedSums) {
      returned += sum;
    }
    assertEquals(expected, returned);
  }

  @Test
  void submitBlocksWhileQueueIsFull() throws Exception {
    pool = new ThreadPool(1);
    CountDownLatch started = new CountDownLatch(1);
    TaskHandle<Void> blocker =
        pool.submit(
            () -> {
              started.countDown();
              awaitQuietly(release);
            });
    started.await();

    int capacity = pool.queueCapacity();
    @SuppressWarnings("unchecked")
    TaskHandle<Void>[] queued = new TaskHandle[capacity];
    for (int i = 0; i < capacity; i++) {
      queued[i] = pool.submit(() -> {});
    }

    AtomicBoolean submitted = new AtomicBoolean();
    AtomicReference<TaskHandle<Void>> overflow = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread producer =
        new Thread(
            () -> {
              try {
                overflow.set(pool.submit(() -> {}));
                submitted.set(true);
              } catch (Throwable t) {
                failure.set(t);
              }
            });
    producer.start();
    awaitParkedOnCondition(producer);

    assertFalse(submitted.get());
    assertFalse(blocker.isDone());
    for (TaskHandle<Void> handle : queued) {
      assertFalse(handle.isDone());
    }

    release.countDown();
    producer.join();
    assertNull(failure.get());
    assertTrue(submitted.get());
    blocker.get();
    for (TaskHandle<Void> handle : queued) {
      handle.get();
    }
    overflow.get().get();
  }

  @Test
  void failingTaskDoesNotKillWorker() throws Exception {
    pool = new ThreadPool(1);
    IllegalStateException boom = new IllegalStateException("boom");
    TaskHandle<Integer> failing =
        pool.submit(
            () -> {
              throw boom;
            });
    ExecutionException thrown = assertThrows(ExecutionException.class, failing::get);
    assertSame(boom, thrown.getCause());
    assertTrue(failing.isDone());

    StackOverflowError error = new StackOverflowError();
    TaskHandle<Void> erroring =
        pool.submit(
            (Runnable)
                () -> {
                  throw error;
                });
    assertSame(error, assertThrows(ExecutionException.class, erroring::get).getCause());

    AtomicInteger completed = new AtomicInteger();
    int tasks = 1_000;
    @SuppressWarnings("unchecked")
    TaskHandle<Integer>[] handles = new TaskHandle[tasks];
    for (int i = 0; i < tasks; i++) {
      int value = i;
      handles[i] =
          pool.submit(
              () -> {
                if (value % 10 == 0) {
                  throw new RuntimeException("task " + value);
                }
                completed.incrementAndGet();
                return value;
              });
    }
    for (int i = 0; i < tasks; i++) {
      if (i % 10 == 0) {
        assertThrows(ExecutionException.class, handles[i]::get);
      } else {
        assertEquals(i, handles[i].get());
      }
    }
    assertEquals(tasks - tasks / 10, completed.get());
  }

  @Test
  void interruptFlagSetByTaskDoesNotLeakIntoNextTask() throws Exception {
    pool = new ThreadPool(1);
    pool.submit(() -> Thread.currentThread().interrupt()).get();
    assertFalse(pool.submit(() -> Thread.currentThread().isInterrupted()).get());
  }

  @Test
  void shutdownDrainsQueuedTasksAndRejectsNewOnes() throws Exception {
    pool = new ThreadPool(2);
    AtomicInteger executed = new AtomicInteger();
    int tasks = pool.workerCount() + pool.queueCapacity();
    @SuppressWarnings("unchecked")
    TaskHandle<Void>[] handles = new TaskHandle[tasks];
    for (int i = 0; i < tasks; i++) {
      handles[i] =
          pool.submit(
              () -> {
                awaitQuietly(release);
                executed.incrementAndGet();
              });
    }

    pool.shutdown();
    assertThrows(IllegalStateException.class, () -> pool.submit(() -> {}));
    assertFalse(pool.awaitTermination(50, TimeUnit.MILLISECONDS));
    assertEquals(0, executed.get());

    release.countDown();
    assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    assertEquals(tasks, executed.get());
    for (TaskHandle<Void> handle : handles) {
      assertTrue(handle.isDone());
      assertNull(handle.get());
    }
  }

  @Test
  void shutdownRejectsProducerBlockedOnFullQueue() throws Exception {
    pool = new ThreadPool(1);
    CountDownLatch started = new CountDownLatch(1);
    pool.submit(
        () -> {
          started.countDown();
          awaitQuietly(release);
        });
    started.await();
    for (int i = 0; i < pool.queueCapacity(); i++) {
      pool.submit(() -> {});
    }

    AtomicReference<Throwable> outcome = new AtomicReference<>();
    Thread producer =
        new Thread(
            () -> {
              try {
                pool.submit(() -> {});
              } catch (Throwable t) {
                outcome.set(t);
              }
            });
    producer.start();
    awaitParkedOnCondition(producer);

    pool.shutdown();
    producer.join();
    assertTrue(outcome.get() instanceof IllegalStateException, String.valueOf(outcome.get()));
    release.countDown();
  }

  @Test
  void awaitTerminationWithIdleWorkersReturnsAfterShutdown() throws Exception {
    pool = new ThreadPool(4);
    pool.shutdown();
    assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
  }

  /**
   * Spins until the thread is parked on a {@code Condition} (not merely contending for the lock),
   * which for a producer means it is waiting on {@code notFull}.
   *
   * <p>Bounded by {@link #PARK_DEADLINE_NANOS}: a busy loop ignores the interrupt JUnit's
   * {@code @Timeout} sends, so without a deadline a thread parked anywhere else would hang the run.
   */
  private static void awaitParkedOnCondition(Thread thread) {
    long start = System.nanoTime();
    while (!(thread.getState() == Thread.State.WAITING
        && LockSupport.getBlocker(thread) instanceof AbstractQueuedSynchronizer.ConditionObject)) {
      Thread.State state = thread.getState();
      if (state == Thread.State.TERMINATED) {
        throw new AssertionError(thread.getName() + " terminated instead of blocking");
      }
      if (System.nanoTime() - start > PARK_DEADLINE_NANOS) {
        throw new AssertionError(
            thread.getName()
                + " did not park on a Condition within "
                + TimeUnit.NANOSECONDS.toSeconds(PARK_DEADLINE_NANOS)
                + " s; state "
                + state
                + ", blocker "
                + LockSupport.getBlocker(thread));
      }
      Thread.onSpinWait();
    }
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
