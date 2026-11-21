package com.coursework.search.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

final class Task<T> implements TaskHandle<T> {
  private static final int PENDING = 0;
  private static final int SUCCEEDED = 1;
  private static final int FAILED = 2;

  private final Callable<T> callable;
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition completed = lock.newCondition();
  private int state = PENDING;
  private T result;
  private Throwable error;

  Task(Callable<T> callable) {
    if (callable == null) {
      throw new NullPointerException("callable");
    }
    this.callable = callable;
  }

  /** Never throws: any failure is stored and reported through {@link #get()}. */
  void run() {
    T value = null;
    Throwable failure = null;
    try {
      value = callable.call();
    } catch (Throwable t) {
      failure = t;
    }
    lock.lock();
    try {
      if (failure == null) {
        result = value;
        state = SUCCEEDED;
      } else {
        error = failure;
        state = FAILED;
      }
      completed.signalAll();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public T get() throws InterruptedException, ExecutionException {
    lock.lock();
    try {
      while (state == PENDING) {
        completed.await();
      }
      if (state == FAILED) {
        throw new ExecutionException(error);
      }
      return result;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean isDone() {
    lock.lock();
    try {
      return state != PENDING;
    } finally {
      lock.unlock();
    }
  }
}
