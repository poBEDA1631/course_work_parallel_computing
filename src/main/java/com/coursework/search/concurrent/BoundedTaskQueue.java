package com.coursework.search.concurrent;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Fixed-capacity ring buffer; producers block while it is full, consumers while it is empty. */
final class BoundedTaskQueue {
  private final Task<?>[] buffer;
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition notEmpty = lock.newCondition();
  private final Condition notFull = lock.newCondition();
  private int head;
  private int tail;
  private int count;
  private boolean closed;

  BoundedTaskQueue(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be >= 1: " + capacity);
    }
    buffer = new Task<?>[capacity];
  }

  /**
   * @throws IllegalStateException if the queue is closed, including while the caller was waiting
   *     for free space
   */
  void put(Task<?> task) throws InterruptedException {
    lock.lock();
    try {
      while (!closed && count == buffer.length) {
        notFull.await();
      }
      if (closed) {
        throw new IllegalStateException("queue is closed");
      }
      buffer[tail] = task;
      tail = (tail + 1) % buffer.length;
      count++;
      notEmpty.signal();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns the next task, or {@code null} once the queue is closed and drained. Uninterruptible so
   * that only {@link #close()} can stop a consumer.
   */
  Task<?> take() {
    lock.lock();
    try {
      while (!closed && count == 0) {
        notEmpty.awaitUninterruptibly();
      }
      if (count == 0) {
        return null;
      }
      Task<?> task = buffer[head];
      buffer[head] = null;
      head = (head + 1) % buffer.length;
      count--;
      notFull.signal();
      return task;
    } finally {
      lock.unlock();
    }
  }

  void close() {
    lock.lock();
    try {
      closed = true;
      notEmpty.signalAll();
      notFull.signalAll();
    } finally {
      lock.unlock();
    }
  }

  int capacity() {
    return buffer.length;
  }
}
