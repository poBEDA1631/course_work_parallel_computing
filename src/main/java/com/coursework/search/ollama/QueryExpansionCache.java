package com.coursework.search.ollama;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded LRU cache: a hash map for lookup plus a doubly linked list ordered from most to least
 * recently used. Both {@link #get} and {@link #put} count as a use. All methods are synchronized,
 * since the map and the list must change together.
 */
public final class QueryExpansionCache {
  public static final int DEFAULT_CAPACITY = 256;

  private static final class Node {
    final String key;
    List<String> value;
    Node prev;
    Node next;

    Node(String key, List<String> value) {
      this.key = key;
      this.value = value;
    }
  }

  private final int capacity;
  private final Map<String, Node> nodes = new HashMap<>();
  private Node head;
  private Node tail;

  public QueryExpansionCache() {
    this(DEFAULT_CAPACITY);
  }

  public QueryExpansionCache(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be >= 1: " + capacity);
    }
    this.capacity = capacity;
  }

  /** Returns the cached value, or {@code null}, and marks the key as most recently used. */
  public synchronized List<String> get(String key) {
    Node node = nodes.get(key);
    if (node == null) {
      return null;
    }
    moveToFront(node);
    return node.value;
  }

  /** Stores an unmodifiable copy; evicts the least recently used entry when over capacity. */
  public synchronized void put(String key, List<String> value) {
    List<String> copy = List.copyOf(value);
    Node node = nodes.get(key);
    if (node != null) {
      node.value = copy;
      moveToFront(node);
      return;
    }
    node = new Node(key, copy);
    nodes.put(key, node);
    linkFirst(node);
    if (nodes.size() > capacity) {
      Node eldest = tail;
      unlink(eldest);
      nodes.remove(eldest.key);
    }
  }

  public synchronized int size() {
    return nodes.size();
  }

  /** Keys from most to least recently used; walks the list, so it also checks the links. */
  synchronized String[] keysByRecency() {
    String[] keys = new String[nodes.size()];
    int i = 0;
    Node previous = null;
    for (Node n = head; n != null; n = n.next) {
      if (i == keys.length || n.prev != previous) {
        throw new IllegalStateException("linked list does not match the map");
      }
      keys[i++] = n.key;
      previous = n;
    }
    if (i != keys.length || previous != tail) {
      throw new IllegalStateException("linked list does not match the map");
    }
    return keys;
  }

  private void moveToFront(Node node) {
    if (node != head) {
      unlink(node);
      linkFirst(node);
    }
  }

  private void linkFirst(Node node) {
    node.prev = null;
    node.next = head;
    if (head != null) {
      head.prev = node;
    }
    head = node;
    if (tail == null) {
      tail = node;
    }
  }

  private void unlink(Node node) {
    if (node.prev != null) {
      node.prev.next = node.next;
    } else {
      head = node.next;
    }
    if (node.next != null) {
      node.next.prev = node.prev;
    } else {
      tail = node.prev;
    }
    node.prev = null;
    node.next = null;
  }
}
