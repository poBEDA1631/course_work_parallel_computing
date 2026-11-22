"use strict";

const HEALTH_INTERVAL_MS = 5000;
const BUILD_POLL_MS = 2000;

const el = (id) => document.getElementById(id);
const banner = el("banner");

function showError(message) {
  banner.textContent = message;
  banner.hidden = false;
}

function clearError() {
  banner.hidden = true;
  banner.textContent = "";
}

/* Ollama badge: last known state, refreshed every 5 s. */

async function refreshHealth() {
  try {
    const response = await fetch("/api/health", { cache: "no-store" });
    const body = await response.json();
    const badge = el("ollama");
    badge.textContent = body.ollama ? "Ollama: online" : "Ollama: offline";
    badge.className = "badge status " + (body.ollama ? "online" : "offline");
  } catch (e) {
    // Keep the last known state when the server itself is unreachable.
  }
}

/* Index block. While a build runs there is no index and /api/stats answers 409 "index build in
   progress", so the page can tell "building" from "not built" even after a reload or when another
   client started the build. */

const BUILDING_MESSAGE = "index build in progress";
let polling = null;

function setBuilding(on) {
  const button = el("build");
  button.disabled = on;
  el("threads").disabled = on;
  if (on) {
    button.textContent = "Building...";
    el("index-status").textContent = "Building index...";
  }
}

/** Returns {state: "ready", stats} | {state: "building"} | {state: "none"}. */
async function fetchIndexState() {
  const response = await fetch("/api/stats", { cache: "no-store" });
  if (response.ok) {
    return { state: "ready", stats: await response.json() };
  }
  if (response.status === 409) {
    const body = await response.json().catch(() => ({}));
    return { state: body.message === BUILDING_MESSAGE ? "building" : "none" };
  }
  throw new Error("stats request failed with HTTP " + response.status);
}

function renderIndexState(index) {
  if (index.state === "building") {
    setBuilding(true);
    return;
  }
  setBuilding(false);
  const status = el("index-status");
  const button = el("build");
  if (index.state === "none") {
    status.textContent = "No index yet.";
    button.textContent = "Build index";
    return;
  }
  const stats = index.stats;
  const seconds = (stats.index_build_time_ms / 1000).toFixed(1);
  status.textContent =
    stats.documents.toLocaleString("en-US") + " documents, built in " + seconds +
    " s with " + stats.threads_used + (stats.threads_used === 1 ? " thread" : " threads");
  button.textContent = "Rebuild index";
}

async function refreshIndex() {
  try {
    const index = await fetchIndexState();
    renderIndexState(index);
    if (index.state === "building") {
      pollWhileBuilding();
    }
    return index;
  } catch (e) {
    el("index-status").textContent = "Index status unavailable.";
    return null;
  }
}

/** Follows a build this page did not start (or started before a reload) until it ends. */
function pollWhileBuilding() {
  if (polling !== null) {
    return;
  }
  polling = setInterval(async () => {
    try {
      const index = await fetchIndexState();
      if (index.state !== "building") {
        clearInterval(polling);
        polling = null;
        renderIndexState(index);
      }
    } catch (e) {
      // Server unreachable for a moment; try again on the next tick.
    }
  }, BUILD_POLL_MS);
}

async function buildIndex() {
  clearError();
  const threads = Number(el("threads").value);
  if (!Number.isInteger(threads) || threads < 1 || threads > 256) {
    showError("Threads must be a whole number from 1 to 256.");
    return;
  }
  setBuilding(true);
  try {
    const response = await fetch("/api/index", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ threads }),
    });
    if (response.status === 409) {
      // Another client is building; the stats show when it is done.
      pollWhileBuilding();
      return;
    }
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      showError(body.message || "Index build failed with HTTP " + response.status + ".");
    }
  } catch (e) {
    showError("Could not reach the server.");
  }
  await refreshIndex();
}

/* Search block: a new WebSocket per query, closed after done or error. Starting a new query
   closes the previous socket, and its late messages are ignored. */

let currentSocket = null;

function search(query) {
  if (currentSocket !== null) {
    currentSocket.close();
    currentSocket = null;
  }
  clearError();
  const expansion = el("expansion");
  const results = el("results");
  results.replaceChildren();
  expansion.replaceChildren(text("span", "Expanding query...", "muted"));

  const scheme = location.protocol === "https:" ? "wss://" : "ws://";
  const socket = new WebSocket(scheme + location.host + "/ws/search");
  currentSocket = socket;
  let finished = false;

  socket.onopen = () => socket.send(JSON.stringify({ query }));
  socket.onmessage = (event) => {
    if (socket !== currentSocket) {
      return;
    }
    const message = JSON.parse(event.data);
    switch (message.stage) {
      case "query_expansion":
        renderExpansion(message.data.expanded_terms);
        break;
      case "keyword_search":
        renderResults(message.data.results, message.data.time_ms);
        break;
      case "done":
        finished = true;
        socket.close();
        break;
      case "error":
        finished = true;
        if (expansion.textContent === "Expanding query...") {
          expansion.replaceChildren();
        }
        showError(message.message);
        socket.close();
        break;
    }
  };
  socket.onclose = () => {
    if (socket === currentSocket) {
      currentSocket = null;
      if (!finished) {
        showError("Connection to the server was lost.");
      }
    }
  };
}

function renderExpansion(terms) {
  const expansion = el("expansion");
  if (terms.length === 0) {
    expansion.replaceChildren(text("span", "No expansion.", "muted"));
    return;
  }
  expansion.replaceChildren(text("span", "Expanded with:", "muted"));
  for (const term of terms) {
    expansion.append(text("span", term, "badge"));
  }
}

function renderResults(results, timeMs) {
  const container = el("results");
  container.replaceChildren(
    text("p", results.length + " found in " + timeMs + " ms", "muted results-meta"));
  if (results.length === 0) {
    container.append(text("p", "No results."));
    return;
  }
  const list = document.createElement("ol");
  list.className = "results";
  results.forEach((result, i) => {
    const item = document.createElement("li");
    item.className = "result";
    const head = document.createElement("div");
    head.className = "result-head";
    head.append(
      text("span", (i + 1) + ".", "rank"),
      text("span", result.doc_id, "doc-id"),
      text("span", result.score.toFixed(2), "score"));
    item.append(head);
    if (result.snippet) {
      item.append(text("p", result.snippet, "snippet"));
    }
    list.append(item);
  });
  container.append(list);
}

/** Creates an element with text content only: snippets come from documents, never as HTML. */
function text(tag, content, className) {
  const node = document.createElement(tag);
  node.textContent = content;
  if (className) {
    node.className = className;
  }
  return node;
}

/* Wiring */

el("build").addEventListener("click", buildIndex);
el("search-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const query = el("query").value.trim();
  if (query !== "") {
    search(query);
  }
});

refreshHealth();
setInterval(refreshHealth, HEALTH_INTERVAL_MS);
refreshIndex();
